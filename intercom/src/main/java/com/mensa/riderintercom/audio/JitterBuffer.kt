package com.mensa.riderintercom.audio

import java.util.TreeMap
import kotlin.random.Random

/**
 * Reorders frames by sequence number and hides the gaps a 2.4 GHz link leaves behind.
 *
 * A single lost frame is concealed by repeating the last one at reducing gain, which the
 * ear reads as a small roughness. Two or more consecutive losses fade into comfort noise
 * rather than silence, so the rider does not think the link has died mid-sentence.
 *
 * **Once playout has started it never returns to silence.** The prefill gate applies only
 * at the very beginning of a stream and after [reset]. An earlier version dropped back
 * into the prefill state on every underrun, which meant one late frame cost a full
 * [targetFrames] of hard digital silence — 60 ms on Bluetooth — instead of 20 ms of
 * concealment. That is audible, and it is what made speech sound jittery on a link that
 * was otherwise fine.
 *
 * The depth adapts: an underrun deepens the buffer by one frame (up to
 * [AudioConstants.JITTER_MAX], 200 ms), and a clean spell walks it back towards the base
 * target so latency does not creep up for the rest of the ride. Concealing consumes no
 * queued frame, so each underrun also rebuilds one slot of depth on its own.
 */
class JitterBuffer(
    targetFrames: Int = AudioConstants.JITTER_TARGET_BT,
) {
    private companion object {
        /** Frames of clean playout before the buffer tries to shrink again (5 s). */
        const val SHRINK_AFTER_CLEAN_FRAMES = 250L
    }

    private val frames = TreeMap<Int, ShortArray>()
    private val lock = Any()

    /** The depth the buffer returns to when the link is behaving. */
    private var baseTarget = targetFrames.coerceIn(1, AudioConstants.JITTER_MAX)

    /** The depth in use now, which grows after underruns. */
    private var targetFrames = baseTarget

    private var nextSeq = -1
    private var started = false
    private var lastFrame: ShortArray? = null
    private var concealedInARow = 0
    private var cleanRun = 0L

    @Volatile var lostFrames: Long = 0L; private set
    @Volatile var playedFrames: Long = 0L; private set
    @Volatile var lateFrames: Long = 0L; private set

    /** Times the buffer ran dry. The number that matters when speech sounds choppy. */
    @Volatile var underruns: Long = 0L; private set

    val depth: Int get() = synchronized(lock) { frames.size }

    /** Current adaptive depth in frames, for the stats line. */
    val targetDepth: Int get() = synchronized(lock) { targetFrames }

    /** Sets the base depth, e.g. when switching transport. Also resets the adaptation. */
    fun setTarget(frames: Int) = synchronized(lock) {
        baseTarget = frames.coerceIn(1, AudioConstants.JITTER_MAX)
        targetFrames = baseTarget
    }

    fun reset() = synchronized(lock) {
        frames.clear()
        nextSeq = -1
        started = false
        lastFrame = null
        concealedInARow = 0
        cleanRun = 0
        targetFrames = baseTarget
    }

    /** Called from the link's reader thread. */
    fun put(seq: Int, pcm: ShortArray) = synchronized(lock) {
        if (nextSeq >= 0 && seqBefore(seq, nextSeq)) {
            // Arrived after its slot was played; dropping it is better than playing it late.
            lateFrames++
            return
        }
        frames[seq] = pcm
        if (nextSeq < 0) nextSeq = seq
        trimIfTooDeep()
    }

    /**
     * Called from the playback thread every 20 ms. Returns true when [out] holds audio to
     * play — real or concealed. It returns false only while the stream is first filling,
     * which is the one time digital silence is the right output.
     */
    fun take(out: ShortArray): Boolean = synchronized(lock) {
        if (!started) {
            if (frames.size < targetFrames) return false
            started = true
        }

        val wanted = nextSeq

        // Anything older than the slot we are waiting for can only be played late.
        while (frames.isNotEmpty() && seqBefore(frames.firstKey(), wanted)) {
            frames.remove(frames.firstKey())
            lateFrames++
        }

        val exact = frames.remove(wanted)
        if (exact != null) {
            nextSeq = (wanted + 1) and 0xFFFF
            concealedInARow = 0
            lastFrame = exact
            playedFrames++
            exact.copyInto(out, 0, 0, minOf(exact.size, out.size))
            cleanRun++
            maybeShrinkTarget()
            return true
        }

        if (frames.isEmpty()) {
            // Underrun. Conceal rather than going silent, and hold nextSeq so the stream
            // resumes exactly where it left off if the frame is merely late. Concealing
            // consumes nothing, so this also rebuilds one slot of depth.
            underruns++
            cleanRun = 0
            growTarget()
            return conceal(out)
        }

        // A later frame is already queued, so this slot is a real loss rather than
        // lateness. Step over it instead of stalling the whole stream.
        lostFrames++
        cleanRun = 0
        nextSeq = (wanted + 1) and 0xFFFF
        return conceal(out)
    }

    /** An underrun means the buffer was too shallow for this link. Deepen it. */
    private fun growTarget() {
        if (targetFrames < AudioConstants.JITTER_MAX) targetFrames++
    }

    /** After a clean spell, walk the depth back down so latency does not creep up. */
    private fun maybeShrinkTarget() {
        if (targetFrames > baseTarget && cleanRun >= SHRINK_AFTER_CLEAN_FRAMES) {
            targetFrames--
            cleanRun = 0
        }
    }

    /**
     * Bounds added latency. A link that stalled and then delivered a burst must not leave
     * the rider permanently a second behind the conversation.
     */
    private fun trimIfTooDeep() {
        val cap = AudioConstants.JITTER_MAX
        while (frames.size > cap) {
            frames.remove(frames.firstKey())
            lateFrames++
        }
        if (frames.isNotEmpty() && nextSeq >= 0 && seqBefore(nextSeq, frames.firstKey())) {
            // Everything we were waiting for was trimmed away; resume at what is left.
            nextSeq = frames.firstKey()
        }
    }

    /** Packet loss concealment: decaying repeat first, then comfort noise. */
    private fun conceal(out: ShortArray): Boolean {
        concealedInARow++
        val last = lastFrame
        if (last != null && concealedInARow == 1) {
            val gain = 0.6
            for (i in out.indices) {
                val v = if (i < last.size) last[i] * gain else 0.0
                out[i] = v.toInt().toShort()
            }
            return true
        }
        // Two or more in a row: low-level comfort noise so the link still sounds alive.
        // After 500 ms it fades out, so a genuinely dead link does go quiet.
        val level = if (concealedInARow > 25) 0 else 120
        if (level == 0) {
            out.fill(0)
        } else {
            for (i in out.indices) out[i] = Random.nextInt(-level, level + 1).toShort()
        }
        return true
    }

    /** Sequence numbers are 16-bit and wrap; compare on the short way round. */
    private fun seqBefore(a: Int, b: Int): Boolean {
        val diff = (a - b) and 0xFFFF
        return diff != 0 && diff > 0x8000
    }

    fun lossPercent(): Double {
        val total = playedFrames + lostFrames
        return if (total == 0L) 0.0 else lostFrames * 100.0 / total
    }
}
