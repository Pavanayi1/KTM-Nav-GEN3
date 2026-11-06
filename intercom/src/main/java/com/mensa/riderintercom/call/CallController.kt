package com.mensa.riderintercom.call

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.media.AudioManager
import android.util.Log
import com.mensa.riderintercom.audio.AdpcmCodec
import com.mensa.riderintercom.audio.AudioConstants
import com.mensa.riderintercom.audio.Beeper
import com.mensa.riderintercom.audio.JitterBuffer
import com.mensa.riderintercom.audio.MicCapture
import com.mensa.riderintercom.audio.NoiseSuppression
import com.mensa.riderintercom.audio.VoicePlayer
import com.mensa.riderintercom.floor.FloorActions
import com.mensa.riderintercom.floor.FloorArbiter
import com.mensa.riderintercom.floor.FloorState
import com.mensa.riderintercom.floor.Vad
import com.mensa.riderintercom.floor.VadEvent
import com.mensa.riderintercom.media.FocusManager
import com.mensa.riderintercom.protocol.Codec
import com.mensa.riderintercom.protocol.Hello
import com.mensa.riderintercom.protocol.Message
import com.mensa.riderintercom.protocol.Protocol
import com.mensa.riderintercom.transport.LinkRole
import com.mensa.riderintercom.transport.LinkState
import com.mensa.riderintercom.transport.PeerLink
import com.mensa.riderintercom.transport.LinkSelector
import com.mensa.riderintercom.transport.Transport
import com.mensa.riderintercom.util.Permissions
import com.mensa.riderintercom.util.Prefs
import com.mensa.riderintercom.util.TalkMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger

/**
 * The session: it owns the link, the audio pipeline and the floor, and it is the only
 * place where the three meet.
 *
 * Send path:  mic -> detector -> arbiter -> encoder -> link
 * Receive path: link -> decoder -> jitter buffer -> track tagged as media
 *
 * The pipeline never learns which transport is underneath; it talks to [PeerLink].
 */
class CallController(
    private val context: Context,
    private val prefs: Prefs,
    private val adapter: BluetoothAdapter,
) {
    companion object {
        private const val TAG = "CallController"
        /** Arbiter watchdogs and the live mic level run on this. */
        private const val TICK_MS = 200L
        /** The full status snapshot only needs to change once a second. */
        private const val STATUS_EVERY_TICKS = 5L
        private const val STATS_TAG = "IntercomStats"
        /** 25 ticks at 200 ms: one stats line every 5 seconds. */
        private const val STATS_EVERY_TICKS = 25L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _status = MutableStateFlow(CallStatus())
    val status: StateFlow<CallStatus> = _status.asStateFlow()

    /**
     * The microphone level, kept out of [CallStatus] on purpose.
     *
     * It changes several times a second, and while it lived in the one big status object
     * every tick invalidated every composable reading any field of it - the whole
     * scrolling screen, the dial included. Split out, only the level meter recomposes.
     */
    private val _micLevel = MutableStateFlow(0f)
    val micLevel: StateFlow<Float> = _micLevel.asStateFlow()

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val jitter = JitterBuffer()
    private val beeper = Beeper()
    private val player = VoicePlayer(jitter, beeper)
    private val vad = Vad(prefs.current.vadSensitivity)
    private val encoder = AdpcmCodec.Encoder()

    private var mic: MicCapture? = null
    private var link: PeerLink? = null
    private var selector: LinkSelector? = null
    private var arbiter: FloorArbiter? = null
    private var ticker: Job? = null

    private val sessionId = Hello.newSessionId()
    private val audioSeq = AtomicInteger(0)
    private val controlSeq = AtomicInteger(0)

    /** 100 ms of audio kept back so the detector's attack delay does not clip a word. */
    private val preroll = ArrayDeque<ShortArray>(AudioConstants.PREROLL_FRAMES)

    private val decodeBuf = ShortArray(AudioConstants.FRAME_SAMPLES)
    private var startedAtMs = 0L

    /** Set while the system owns the headset, e.g. a real phone call. */
    @Volatile private var suspendedForInterruption = false

    val focus = FocusManager(
        audioManager = audioManager,
        onFocusLost = { permanent -> onInterruption(permanent) },
        onFocusRegained = { onInterruptionOver() },
    )

    // ------------------------------------------------------------------- start

    fun start(role: LinkRole, device: BluetoothDevice?) {
        if (_status.value.phase != CallPhase.Idle && _status.value.phase != CallPhase.Ended &&
            _status.value.phase != CallPhase.Error
        ) return

        if (!Permissions.hasMicrophone(context)) {
            fail("Microphone permission is not granted")
            return
        }
        if (!Permissions.hasBluetooth(context)) {
            fail("Bluetooth permission is not granted")
            return
        }
        if (!adapter.isEnabled) {
            fail("Bluetooth is off")
            return
        }

        val settings = prefs.current
        focus.takeFocus = settings.takeAudioFocus
        vad.sensitivity = settings.vadSensitivity
        applyVoiceLevel(settings.voiceLevelDb, settings.takeAudioFocus)

        startedAtMs = System.currentTimeMillis()
        update {
            it.copy(
                phase = CallPhase.Starting,
                isHost = role == LinkRole.Host,
                statusText = if (role == LinkRole.Host) "Waiting for buddy" else "Connecting",
                error = null,
            )
        }

        val hello = Hello(
            version = Protocol.VERSION,
            sampleRate = AudioConstants.SAMPLE_RATE,
            frameSamples = AudioConstants.FRAME_SAMPLES,
            codec = Hello.CODEC_ADPCM,
            sessionId = sessionId,
            riderName = settings.riderName,
        )

        val arb = FloorArbiter(sessionId, floorActions)
        arbiter = arb

        // The selector is itself a PeerLink: it owns the radios and swaps them without
        // the audio pipeline ever learning which one is underneath.
        val l = LinkSelector(
            context = context,
            adapter = adapter,
            role = role,
            peerDevice = device,
            localHello = hello,
            preference = settings.transport,
        )
        selector = l
        link = l
        arb.rttMillisProvider = { l.rttMillis.value }
        l.setOnMessage(::onMessage)
        l.start()

        if (!player.start()) {
            fail("Could not open the audio output")
            return
        }

        val m = MicCapture(
            audioManager = audioManager,
            source = settings.micSource,
            noiseSuppression = settings.noiseSuppression,
            onFrame = ::onMicFrame,
        )
        if (!m.start()) {
            fail("Could not open the microphone")
            return
        }
        mic = m

        observeLink(l)
        startTicker(l, arb)
    }

    private fun observeLink(l: PeerLink) {
        scope.launch {
            l.state.collect { st ->
                val phase = when (st) {
                    LinkState.Idle -> CallPhase.Starting
                    LinkState.Listening, LinkState.Connecting, LinkState.Handshaking -> CallPhase.Connecting
                    LinkState.Connected -> CallPhase.Active
                    LinkState.Reconnecting -> CallPhase.Reconnecting
                    LinkState.Failed -> CallPhase.Error
                    LinkState.Closed -> CallPhase.Ended
                }
                if (st == LinkState.Connected) {
                    // A fresh socket must not replay the previous one's audio.
                    jitter.reset()
                    arbiter?.reset()
                    vad.reset()
                    preroll.clear()
                } else if (st == LinkState.Reconnecting) {
                    jitter.reset()
                    arbiter?.onLinkLost()
                    focus.undock()
                }
                update { it.copy(phase = phase, link = st) }
            }
        }
        scope.launch { l.status.collect { s -> update { it.copy(statusText = s) } } }
        scope.launch { l.rttMillis.collect { r -> update { it.copy(rttMs = r) } } }
        scope.launch {
            l.peerHello.collect { h -> update { it.copy(peerName = h?.riderName) } }
        }
    }

    private fun startTicker(l: PeerLink, arb: FloorArbiter) {
        ticker?.cancel()
        var ticks = 0L
        ticker = scope.launch {
            while (isActive) {
                delay(TICK_MS)
                arb.tick()
                val micRms = mic?.lastRms ?: 0.0
                // The switch rules run on this number, so it has to be current.
                selector?.lossPercent = jitter.lossPercent()
                _micLevel.value = (micRms / 6000.0).coerceIn(0.0, 1.0).toFloat()
                ticks++

                // One greppable line every 5 s. On the road nobody is watching the
                // screen, so this is the only record of how the link actually behaved.
                if (ticks % STATS_EVERY_TICKS == 0L) {
                    Log.i(
                        STATS_TAG,
                        "link=${l.state.value} floor=${arb.state.value} " +
                            "rtt=${l.rttMillis.value}ms " +
                            "loss=%.1f%% ".format(jitter.lossPercent()) +
                            "jitter=${jitter.depth}/${jitter.targetDepth}f " +
                            "played=${jitter.playedFrames} lost=${jitter.lostFrames} " +
                            "late=${jitter.lateFrames} underruns=${jitter.underruns} " +
                            "micRms=${micRms.toInt()} vadMargin=%.1fdB ".format(vad.lastMarginDb) +
                            "trackUnderruns=${player.trackUnderruns} " +
                            "dropSend=${selector?.droppedSends ?: 0} " +
                            "ns=${mic?.noiseSuppression} hwfx=${mic?.platformEffectsReport} " +
                            "focus=${focus.isHeld} ptt=${arb.pushToTalk} " +
                            "t=${(System.currentTimeMillis() - startedAtMs) / 1000}s",
                    )
                }
                if (ticks % STATUS_EVERY_TICKS == 0L) {
                    update {
                        it.copy(
                            floor = arb.state.value,
                            muted = arb.state.value == FloorState.Muted,
                            pushToTalk = arb.pushToTalk,
                            lossPercent = jitter.lossPercent(),
                            jitterDepth = jitter.depth,
                            focusHeld = focus.isHeld,
                            elapsedSeconds = if (startedAtMs == 0L) 0
                            else (System.currentTimeMillis() - startedAtMs) / 1000,
                        )
                    }
                }
            }
        }
    }

    // --------------------------------------------------------------- send path

    /** Called on the capture thread, once per 20 ms. */
    private fun onMicFrame(frame: ShortArray) {
        if (suspendedForInterruption) return
        val arb = arbiter ?: return
        val l = link ?: return

        // Capture keeps running in all three floor states so the detector can fire;
        // only Talking feeds the encoder.
        val event = vad.process(frame)

        // Until a peer is actually on the other end, run capture and the detector - the
        // level meter and the noise floor both need them - but take no floor, duck
        // nothing and queue nothing. Encoding into a socket that does not exist yet only
        // burns CPU and fills the outbox: while merely waiting to be joined this pushed
        // dropSend past 1700 frames in 45 seconds and ducked the rider's music the whole
        // time.
        if (l.state.value != LinkState.Connected) {
            rememberForPreroll(frame)
            return
        }

        // In push-to-talk the detector still runs - it drives the level meter and keeps
        // the noise floor current - but only the button takes the floor.
        if (prefs.current.talkMode == TalkMode.Open) {
            when (event) {
                VadEvent.Open -> {
                    val startMicros = System.nanoTime() / 1000L -
                        (AudioConstants.FRAME_MS * 1000L * vad.sensitivity.attackFrames)
                    if (arb.onVadOpen(startMicros)) {
                        focus.duck()
                        flushPreroll(l)
                    }
                }
                VadEvent.Close -> arb.onVadClose()
                VadEvent.None -> Unit
            }
        }

        if (arb.state.value == FloorState.Talking) {
            sendAudio(l, frame)
        } else {
            rememberForPreroll(frame)
        }
    }

    private fun rememberForPreroll(frame: ShortArray) {
        if (preroll.size >= AudioConstants.PREROLL_FRAMES) preroll.removeFirst()
        preroll.addLast(frame.copyOf())
    }

    /** Sends the held-back frames so the first syllable is not lost to the attack delay. */
    private fun flushPreroll(l: PeerLink) {
        while (preroll.isNotEmpty()) {
            sendAudio(l, preroll.removeFirst())
        }
    }

    private fun sendAudio(l: PeerLink, frame: ShortArray) {
        val payload = encoder.encodeFrame(frame)
        val seq = audioSeq.getAndIncrement() and 0xFFFF
        l.send(Codec.encode(Protocol.TYPE_AUDIO, seq, 0, payload))
    }

    // ------------------------------------------------------------ receive path

    /** Called on the link's reader thread. */
    private fun onMessage(m: Message) {
        val arb = arbiter ?: return
        when (m.type) {
            Protocol.TYPE_AUDIO -> {
                // Any frame from the holder refreshes the watchdog that would otherwise
                // free the floor after a link drop mid-sentence.
                arb.onRemoteAudio()
                val n = AdpcmCodec.decodeFrame(m.payload, decodeBuf)
                if (n > 0) {
                    jitter.put(m.seq, decodeBuf.copyOf(n))
                    focus.duck()
                }
            }
            Protocol.TYPE_FLOOR_TAKEN -> {
                val remoteTs = Codec.readLong(m.payload, 0)
                val remoteSession = if (m.payload.size >= 16) Codec.readLong(m.payload, 8) else 0L
                val offset = link?.clockOffsetMicros ?: 0L
                // Convert the buddy's stamp into this phone's clock before comparing.
                arb.onRemoteFloorTaken(remoteTs - offset, remoteSession, _status.value.peerName)
                focus.duck()
            }
            Protocol.TYPE_FLOOR_RELEASE -> {
                arb.onRemoteFloorRelease()
                focus.undock()
            }
            Protocol.TYPE_FLOOR_DENIED -> {
                arb.onRemoteFloorDenied(_status.value.peerName)
            }
            Protocol.TYPE_END -> {
                update { it.copy(statusText = "Buddy ended the call") }
                stop()
            }
            else -> Log.d(TAG, "ignoring ${Protocol.typeName(m.type)}")
        }
    }

    // ------------------------------------------------------------ floor wiring

    private val floorActions = object : FloorActions {
        override fun sendFloorTaken(startedAtMicros: Long) {
            val body = ByteArray(16)
            putLong(body, 0, startedAtMicros)
            putLong(body, 8, sessionId)
            link?.send(
                Codec.encode(
                    Protocol.TYPE_FLOOR_TAKEN,
                    controlSeq.getAndIncrement() and 0xFFFF,
                    0,
                    body,
                )
            )
        }

        override fun sendFloorRelease() {
            link?.send(
                Codec.encode(
                    Protocol.TYPE_FLOOR_RELEASE,
                    controlSeq.getAndIncrement() and 0xFFFF,
                    0,
                    null,
                )
            )
            focus.undock()
        }

        override fun sendFloorDenied() {
            link?.send(
                Codec.encode(
                    Protocol.TYPE_FLOOR_DENIED,
                    controlSeq.getAndIncrement() and 0xFFFF,
                    0,
                    null,
                )
            )
        }

        override fun beep() {
            if (prefs.current.beepWhenMuted) beeper.request()
        }

        override fun onStateChanged(state: FloorState) {
            update { it.copy(floor = state, muted = state == FloorState.Muted) }
            if (state == FloorState.Idle) focus.undock() else focus.duck()
        }
    }

    // ------------------------------------------------------------ interruption

    /**
     * A real phone call takes the headset to HFP and the system takes focus. Capture
     * pauses, the link stays alive on its keep-alive pings, and the output is re-opened
     * afterwards because the A2DP route has been torn down and rebuilt underneath.
     */
    private fun onInterruption(permanent: Boolean) {
        suspendedForInterruption = true
        update { it.copy(statusText = if (permanent) "Audio taken by another app" else "Paused for a call") }
    }

    private fun onInterruptionOver() {
        suspendedForInterruption = false
        jitter.reset()
        vad.reset()
        preroll.clear()
        player.restart()
        update { it.copy(statusText = "Resumed") }
    }

    // ----------------------------------------------------------------- control

    fun setPushToTalk(down: Boolean) {
        arbiter?.setPushToTalk(down, _status.value.peerName)
        if (down) focus.duck() else focus.undock()
        update { it.copy(pushToTalk = down) }
    }

    fun togglePushToTalk() = setPushToTalk(!(arbiter?.pushToTalk ?: false))

    /** The radio currently carrying the call, for the status line. */
    val activeTransport: Transport? get() = selector?.activeTransport?.value

    /** True while the selector is moving the session between radios. */
    val isSwitchingTransport: Boolean get() = selector?.switching?.value == true

    /** The rider asking for the other radio by hand. */
    fun switchTransport(target: Transport) { selector?.switchNow(target) }

    /**
     * Steps Off -> Light -> Strong -> Off and applies it to the live capture path, so the
     * rider can judge the setting against the actual wind instead of in a quiet car park.
     */
    fun cycleNoiseSuppression(): NoiseSuppression {
        val next = when (prefs.current.noiseSuppression) {
            NoiseSuppression.Off -> NoiseSuppression.Light
            NoiseSuppression.Light -> NoiseSuppression.Strong
            NoiseSuppression.Strong -> NoiseSuppression.Off
        }
        prefs.update { it.copy(noiseSuppression = next) }
        mic?.noiseSuppression = next
        return next
    }

    fun applySettingsChange() {
        val s = prefs.current
        vad.sensitivity = s.vadSensitivity
        // Suppression strength changes take effect on the next frame; no call restart.
        mic?.noiseSuppression = s.noiseSuppression
        focus.takeFocus = s.takeAudioFocus
        applyVoiceLevel(s.voiceLevelDb, s.takeAudioFocus)
    }

    private fun applyVoiceLevel(voiceLevelDb: Int, takeFocus: Boolean) {
        // With no focus taken the music is not ducked, so the voice is lifted instead.
        val boost = if (takeFocus) 0.0 else FocusManager.NO_FOCUS_VOICE_BOOST_DB
        player.setGainDb(voiceLevelDb.toDouble() + boost)
    }

    fun stop() {
        ticker?.cancel()
        ticker = null
        mic?.stop()
        mic = null
        link?.close()
        link = null
        selector = null
        player.stop()
        focus.release()
        jitter.reset()
        arbiter?.reset()
        arbiter = null
        preroll.clear()
        encoder.reset()
        update {
            it.copy(
                phase = CallPhase.Ended,
                link = LinkState.Closed,
                floor = FloorState.Idle,
                statusText = "Call ended",
                rttMs = -1,
            )
        }
    }

    fun destroy() {
        stop()
        scope.cancel()
    }

    private fun fail(message: String) {
        Log.e(TAG, message)
        update { it.copy(phase = CallPhase.Error, error = message, statusText = message) }
        mic?.stop(); mic = null
        link?.close(); link = null
        player.stop()
        focus.release()
    }

    private inline fun update(block: (CallStatus) -> CallStatus) {
        _status.value = block(_status.value)
    }

    private fun putLong(dst: ByteArray, off: Int, v: Long) {
        ByteBuffer.wrap(dst, off, 8).order(ByteOrder.BIG_ENDIAN).putLong(v)
    }
}
