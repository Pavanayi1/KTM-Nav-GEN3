# Rider Intercom — phase 1

A rider-to-rider intercom that links two Android phones directly over Bluetooth Classic
RFCOMM, carries voice on the **media** channel so each rider's music keeps playing in
stereo, and decides who talks with voice-activated floor control.

Built from `Rider Intercom App – Bluetooth Phone-to-Phone Call Architecture.md`.
This is the **phase 1** scope from that document: two riders, Bluetooth only, always-on
call, first-speaker-wins arbitration, music ducking.

Status: **working between two phones; not yet road-tested.** A 3m 24s call ran
end to end on 6 Nov 2026 — link, handshake, codec, floor arbitration and audible
two-way speech. 33 JVM tests cover the codec, the wire protocol, the jitter buffer, the
voice detector and the floor arbiter. Verified on a realme RMX5264 running **Android 16**,
one release beyond the targetSdk.

Noise suppression on the transmitted voice is built (three stages, see below). Still
unverified: music ducking, the headset button, reconnect under real dropouts, range,
wind at speed, screen-off survival and battery drain. Those are the phase-1 gate and they
need a ride, not a bench — see *Testing* below.

---

## Build and install

The project needs a JDK that macOS will let run a JIT. Android Studio's bundled runtime
works:

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
```

Build the debug APK:

```bash
./gradlew :app:assembleDebug
```

Run the test suite:

```bash
./gradlew :app:testDebugUnitTest
```

Install on both phones (they each need the app):

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## Using it

1. **Pair the two phones once** in Android's own Bluetooth settings. The app never
   triggers pairing itself; it lists devices Android has already bonded.
2. On both phones, open the app and grant microphone, Bluetooth and notification
   permissions.
3. One rider taps **Host** and waits. The other picks them from the paired list and taps
   **Join**.
4. Talk. The channel opens on your voice and closes 500 ms after you stop. If your buddy
   is already talking you hear a short beep instead of being heard.
5. **Hold to talk** overrides the detector — useful at speed, or to cut in.

On Xiaomi, Oppo, vivo, OnePlus, Samsung and Huawei phones, work through
**Keeping the call alive** on the start screen before a long ride. Those vendors kill
foreground services regardless of what standard Android says, and that is the single most
common way a call dies ten minutes after the screen goes off.

---

## How it is put together

Every phone runs the same stack. The send path captures the mic, decides who holds the
floor, and encodes. The receive path decodes into a jitter buffer and plays through an
`AudioTrack` tagged `USAGE_MEDIA`. A foreground service owns the call.

```
mic ──► MicCapture ──► Vad ──► FloorArbiter ──► AdpcmCodec ──► PeerLink ──► radio
                                    │
radio ──► PeerLink ──► AdpcmCodec ──► JitterBuffer ──► VoicePlayer ──► headset (A2DP)
                                    │
                              FocusManager ──► ducks the music app
```

| Package | What lives there |
| --- | --- |
| `protocol/` | Wire format: 7-byte framed header, `HELLO` handshake, message types |
| `transport/` | `PeerLink` interface and `RfcommLink`, the Bluetooth implementation |
| `audio/` | Capture, ADPCM codec, jitter buffer, media-tagged playback, mute beep, biquads |
| `floor/` | Voice detector and the first-speaker-wins arbiter |
| `media/` | Audio-focus ducking and the headset-button media session |
| `call/` | `IntercomService` (foreground) and `CallController` (the session) |
| `ui/` | Compose screens: start, call, settings, OEM guidance |
| `util/` | Permissions, per-vendor battery guidance, settings storage |

### The rule everything else follows

**The app never opens the Bluetooth headset microphone.** The moment any app does,
Android switches the headset from A2DP to HFP, suspends A2DP output, and music collapses
to mono telephone quality for the rest of the call. So:

- capture uses `VOICE_RECOGNITION`, never `VOICE_COMMUNICATION`, because the latter moves
  the audio mode to `MODE_IN_COMMUNICATION` and routes to SCO on most phones;
- playback is a plain `AudioTrack` with `USAGE_MEDIA` + `CONTENT_TYPE_SPEECH`, which sits
  next to the music app in the mixer;
- the track is never stopped mid-call — it writes silence during gaps, so A2DP never
  renegotiates;
- the mute beep is mixed into that same track rather than played on its own.

The headset mic is still offered in settings, labelled with what it costs.

### Connection handling

`RfcommLink` carries the whole list of known RFCOMM failure modes and their fixes:
discovery is cancelled before every dial, a failed socket is discarded rather than reused,
the insecure socket is tried when the secure one fails SDP, the undocumented reflection
channel is the last attempt only, and the server socket is always closed so the channel
is not still held on the next run.

The session outlives any single socket. Reconnect backs off 1, 2, 4, 8 seconds then
repeats, the host keeps `accept()` open throughout, and buffers are flushed on reconnect
so the rider never hears a burst of stale speech.

### Floor control

A rider's detector opening starts the stream **immediately** and announces it in the same
breath — it does not wait for a grant, because a round trip here is 30-80 ms and waiting
clips the first word. The two announcements are then reconciled by timestamp, using a
clock offset both phones measure from the ping/pong exchange. Ties inside 20 µs go to the
lower session ID. A 100 ms pre-roll buffer covers the detector's attack delay so the first
syllable survives.

Two watchdogs stop the channel jamming: a talker is cut off after 30 seconds of continuous
hold, and the floor frees itself if no audio arrives from the holder for 2 seconds, which
covers a link drop mid-sentence.

---

## Where this differs from the blueprint, and why

Three deliberate substitutions. All three sit behind interfaces so the planned component
drops in without touching anything around it.

**Codec: IMA ADPCM instead of Opus.** The document specifies Opus at 16-24 kbps. Android's
`MediaCodec` Opus *encoder* is absent or unreliable on a large share of devices, and the
documented fallback is bundling `libopus` through JNI — which needs the NDK and a native
build. ADPCM ships working today: 163 bytes per 20 ms frame, about 65 kbps, well inside
what a phone-to-phone RFCOMM link carries, and every frame decodes independently so one
lost frame costs exactly 20 ms instead of desynchronising the decoder. The cost is
bandwidth and some quality versus Opus. `AdpcmCodec` is the only thing that needs
replacing to move to Opus, plus the codec byte already negotiated in `HELLO`.

**Voice detection: band-pass and energy, no Silero.** The document specifies a four-stage
detector ending in the Silero neural model. Stages 1, 2 and 4 are implemented — a
300-3400 Hz band, an adaptive noise floor that only learns from non-speech frames, and
3-frame attack with 25-frame release. Stage 3, the neural confirmation, is not: it needs a
model file and an ONNX or TFLite runtime. One real finding from testing: a *single*
second-order high-pass is not steep enough — loud 80 Hz wind still opened the floor — so
the band-pass is two cascaded sections, 24 dB per octave, which puts rumble about 46 dB
down. There is a test for exactly this.

**Transport: Bluetooth RFCOMM only.** Wi-Fi Direct and Nearby Connections are phase 2 in
the document and are not built. The `PeerLink` interface is the seam they slot into — the
audio pipeline never learns which radio is underneath. The Range option appears in
settings, disabled.

## Not built (phase 2 and beyond)

- Wi-Fi Direct and Nearby Connections transports, and the automatic switching rules
- Groups above two riders (star topology with the lead rider as hub)
- Music sharing between phones
- Mesh relay between riders
- iOS
- WebRTC software echo cancellation (not needed with in-helmet speakers and a boom mic;
  add it if road testing shows echo)

---

## Testing

### What is already verified

33 JVM tests, run with `./gradlew :app:testDebugUnitTest`:

- **Codec** — frame size, round-trip SNR above 20 dB, that a frame decodes standalone
  after the preceding frames were lost, that silence stays silent
- **Protocol** — handshake round-trip, name truncation, that the reader recovers messages
  the stream split or coalesced, and that it resynchronises after garbage
- **Jitter buffer** — prefill, reordering, single-loss concealment, comfort noise across a
  multi-frame gap, late frames dropped, reconnect flush
- **Voice detector** — quiet does not open, speech opens then closes on the release
  window, loud low-frequency rumble does *not* open, a continuous talker is not cut off
- **Floor arbiter** — first speaker streams at once, remote turn mutes, talking while
  muted beeps locally and sends nothing, tie-break by timestamp in both directions,
  dead-heat by session ID, push-to-talk overriding both the detector and a remote turn,
  both watchdogs

### First two-phone call — 6 Nov 2026, 3m 24s

A real call ran between two phones for 3 minutes 24 seconds, with **audible two-way
speech confirmed by both riders**. What the logs prove:

- **The link works.** RFCOMM socket connected on channel 23, `mSocketState: CONNECTED`,
  and it stayed up for the whole call.
- **The handshake and clock sync work.** Floor messages only flow on a handshaked link,
  and the tie-break resolved a cross-phone timestamp comparison, which means the
  ping/pong clock offset was being applied.
- **Audio ran continuously.** `AudioTrack` delivered 3,279,360 frames at 16 kHz —
  exactly 205 s, the whole call — without a stop. That is the design working: the track
  is never stopped mid-call, so A2DP never renegotiates.
- **Floor arbitration fired for real**, including a genuine tie-break when both riders
  spoke at once, and ordinary turn-taking in both directions.
- **Teardown is clean and correctly ordered**: `AudioRecord.stop` → `BluetoothSocket.close`
  → read loop ends → `AudioTrack.stop` → audio focus abandoned. No crash, no leak.

Two things that call changed:

- **The tie-break log was misleading.** It read `tie-break lost by 6731us`, which sounds
  like the timestamps decided it. They did not: 6731 µs is inside the 20 ms dead-heat
  window, so the session ID decided it. The log now names the rule that fired and prints
  the RTT beside it.
- **Worth watching: the dead-heat window may be too tight.** The clock offset is only
  accurate to about half the round-trip time. On RFCOMM that is 15-40 ms, which is *wider
  than the 20 ms dead-heat window itself* — so in practice almost every simultaneous start
  is decided by session ID rather than by who actually spoke first. The constant is
  `FloorArbiter.TIE_DEAD_HEAT_MICROS` and it comes straight from the blueprint. Making it
  scale with measured RTT would be the honest fix, but that is a design change, so it is
  flagged rather than made. The log now gives you the numbers to judge it.

### Jittery audio — found and fixed, 6 Nov 2026

Speech on that first call was intelligible but intermittently choppy. The cause was in
`JitterBuffer`, and it was a logic fault rather than a tuning problem.

The buffer has a prefill gate: it holds back until `targetFrames` have arrived before
playout starts, and `take()` returns false while filling. `VoicePlayer` writes hard
zeroes when `take()` returns false, which is correct at the very start of a stream.

The bug was that **any** underrun dropped the buffer back into that prefill state. So one
late frame did not cost 20 ms of concealment — it cost a full re-fill of digital silence,
60 ms on Bluetooth, and it happened every time the buffer ran dry. The concealment code
was right there and simply never ran.

Fixed by separating the two cases. The prefill gate now applies only at the start of a
stream and after `reset()`. Once playout has begun the buffer never returns silence: an
underrun conceals, holds its place in the sequence so a merely-late frame still plays when
it lands, and because concealing consumes no queued frame it rebuilds a slot of depth on
its own.

The depth is now adaptive as well, which the blueprint asked for and the first version did
not implement: an underrun deepens the buffer by one frame up to the 200 ms ceiling, and a
clean spell walks it back toward the base target so latency does not creep up over a ride.
Five regression tests cover it.

Three counters were added so the next call can distinguish causes that look identical from
the saddle:

| Counter | What it means | What to do about it |
| --- | --- | --- |
| `underruns` | Frames had not arrived from the radio in time | Link or range problem; the buffer now self-deepens |
| `trackUnderruns` | The app did not feed `AudioTrack` fast enough | Scheduling or output buffer size, not the radio |
| `dropSend` | The radio would not accept a frame for sending | Send-side congestion; look at RFCOMM throughput |

## The interface

Rebuilt to a supplied mockup: orange accent on near-black, a five-bar wave mark, a hero
band, and a glowing mic dial as the centrepiece. The palette in `ui/Theme.kt` is sampled
pixel-for-pixel from that mockup rather than approximated, so the build and the reference
agree.

The rule the layout follows is that **the dial's colour is the whole message** — orange
for a clear channel, green when you hold it, amber when your buddy does. A rider reads
that from the corner of their eye at speed; everything else on the screen is secondary.

| File | What it holds |
| --- | --- |
| `ui/Theme.kt` | The sampled palette and type scale |
| `ui/Components.kt` | Wave mark, cards, action tiles, status pill, mic dial, footer, buttons |
| `ui/StartScreen.kt` | Host / Join tiles, state pill, dial, buddy picker |
| `ui/CallScreen.kt` | The in-ride screen; dial, push-to-talk, meters, link detail |
| `ui/SettingsScreen.kt`, `ui/OemScreen.kt` | Same card language |

**The hero image is a placeholder and must be replaced before release.** It is cropped
from the supplied mockup, so its provenance and licence are unknown. Swap
`res/drawable-nodpi/hero_riders.png` for a photo you own or have licensed. Everything
else on screen — the wave mark, the helmet, the dial, the launcher icon — is drawn in
code or as a vector, so nothing else carries that problem.

### Two bugs the rebuild exposed

Running the new screens on a real phone immediately surfaced faults the unit tests could
not see, because both only appear while a call is up:

- **The send path ran before there was anyone to send to.** While merely waiting to be
  joined, the detector opened the floor, the encoder ran, and every frame was queued into
  an outbox with no socket behind it: `dropSend` reached 1738 frames in 45 seconds. Worse,
  audio focus was taken the whole time, so the rider's music ducked while nothing was
  happening. Capture and the detector now keep running — the level meter and the noise
  floor need them — but the floor, the encoder and the focus request all wait for
  `LinkState.Connected`. After the fix the same test reads `dropSend=0 floor=Idle
  focus=false`.
- **The waiting state said "Waiting for buddy" three times** on one screen, because the
  status pill, the dial caption and the detail line all fell back to the same string.

## Build a release APK

Do this before judging how the app feels. **The debug build stutters and the release
build does not** — measured on the same phone, same scroll, same screens:

| | Janky frames | 50th | 90th | 99th | Slow UI thread | Size |
| --- | --- | --- | --- | --- | --- | --- |
| Debug | 7.5% | 14 ms | 22 ms | 32 ms | 20 | 26 MB |
| Release | **0.0%** | 11 ms | 14 ms | 15 ms | **0** | **2.6 MB** |

A debug Compose build carries instrumentation and skips R8; it is not representative.

```bash
./gradlew :app:assembleRelease
```

It is signed with the local debug keystore so it installs for testing. **Replace that with
a real upload key before anything goes to Play** — see `signingConfigs` in
`app/build.gradle.kts`.

Three code fixes went in alongside, and they matter on both build types:

- **Brushes were being allocated inside the dial's `DrawScope`**, so the gradient shader
  was rebuilt on every frame of a 210dp circle. They are built once now, and the halo is
  stacked translucent circles instead of a radial-gradient shader.
- **The dial's pulse animation ran even when idle.** An infinite transition animating
  1f to 1f still drives the choreographer every frame. The transition is only composed
  when something is actually happening.
- **The live mic level lived in the one big `CallStatus` object**, so every level change
  invalidated every composable reading any field of it — the whole scrolling screen. It
  is its own `StateFlow` now, and the heavy snapshot only rebuilds once a second.

## How you talk: open call or push-to-talk

Settings > How you talk.

- **Open call** (default) — the continuous, voice-activated call this app is for. Speak
  and the channel is yours; the buddy's mic mutes until you stop.
- **Push to talk** — the detector never opens the channel. Only the on-screen button, the
  notification action or the headset button do. For helmets too noisy for a detector.

In open call the big slab shrinks to a "Hold to cut in" override, because speaking is the
primary way in and a giant button implying otherwise would be a lie.

## Host is orange, joiner is blue

The screen recolours by role: the host's phone is orange, the joiner's is blue. Wave
mark, tiles, dial, buttons, selected-card tint and links all follow. Two riders comparing
phones at a stop can see instantly which one is hosting, and a screenshot in a bug report
says which end it came from. It is one `CompositionLocal` (`LocalAccents`), so no screen
threads a colour through by hand.

## The call notification

Ongoing, silent, `CATEGORY_CALL`, and it cannot be swiped away. It carries:

- a **Stop** action, always last and always present — the one a gloved hand reaches for,
  so it never moves between states;
- a **Noise** action that cycles Off → Light → Strong on the live capture path, so the
  setting can be judged against real wind rather than in a car park;
- a **Talk** action, only in push-to-talk mode, where it does something the rider wants.

Sub-text shows the talk mode and noise level, so the state is readable without opening
the app. Verified on-device: tapping Stop leaves zero service records and zero posted
notifications.

## Automatic transport switching

The session survives a change of radio with no rider intervention. The audio pipeline
never learns which one is underneath: `LinkSelector` is itself a `PeerLink` that owns the
others and swaps them.

**Both phones have to move together**, which is the part that is easy to get wrong. Two
mechanisms cover the two ways a link goes bad:

- *The link still works but is poor.* The side that decides sends `TYPE_SWITCH` naming
  the target, waits 400 ms so it gets out, then moves. The peer follows deliberately.
- *The link is already dead.* No message can get through, so each side falls back on its
  own timer and tries the other radio. They converge because the order is fixed.

The session ID in the handshake is unchanged across a switch, so the far side treats the
new connection as the same call.

### When it switches

| Rule | Trigger | Goes to |
| --- | --- | --- |
| Loss | connected, frame loss over 5% continuously for 10 s | the other radio |
| Unreachable | not connected for 20 s (about 1+2+4+8+8 s of backoff) | the other radio |
| Group timeout | Wi-Fi Direct given 30 s and no group formed | back to Bluetooth |

Two guards stop it oscillating: a 20 s minimum dwell on each transport, and a cap of six
switches per session. A rider on a bad stretch should not have the app flapping all ride.
Picking **Bluetooth** in settings pins it and is never overridden.

`SwitchPolicy` holds these rules and is free of Android types, so all eleven cases are
unit-tested directly rather than inferred from a road test. One real bug came out of
writing them: the generic 20 s dead-link rule fired before Wi-Fi Direct's documented 30 s
group-forming allowance, so that allowance never actually happened.

**Wi-Fi Direct itself is built but not exercised on hardware.** The group handshake needs
two phones in the same place. `WifiDirectLink` follows the field-tested pattern — the host
calls `createGroup()` and is always the group owner at 192.168.49.1, the joiner only ever
connects to it, discovery is re-issued on a timer because it is not continuous, and a
`WifiLock` stops Android drifting back to a saved network. Voice goes over UDP and control
over TCP. Treat the first Range-mode ride as its bring-up.

## Finding a host without pairing first

When a rider hosts, their phone advertises over BLE and nearby phones running the app show
a **"A rider is hosting"** card with a Join button.

Discovery and the call are deliberately different radios. The voice link needs RFCOMM
(which needs the phones bonded) or Wi-Fi Direct; neither can say "I am here" to a
stranger's phone unprompted. A BLE advertisement can, costs almost nothing and needs no
pairing — so it carries the invitation and the call is set up afterwards.

The 31-byte advertisement is tight (a 128-bit service UUID alone takes 18), so the UUID
goes in the advertisement and the rider's name in the scan response. Hosts unseen for 12 s
drop off the list, so a rider who has ridden away stops being offered.

**Scanning only runs while the start screen is in front.** A background scan would need
its own foreground service and would sit on the radio all ride for something only used at
the start of one. If the buddy is not yet bonded the card offers *Pair* instead of *Join*,
because RFCOMM cannot connect to an unbonded phone.

## Noise cancellation

Settings > Noise cancellation: **Off / Light / Strong**. Default is Light.

### What this is, and what it is not

It is **not** the active noise cancellation an aviation headset does, and no app can be.
ANC works by playing an anti-phase signal into your ear from a microphone millimetres
away, closing the loop in well under a millisecond. Nothing reachable from an Android app
can do that: the path out to the headset is 150-250 ms, roughly a thousand times too
slow, and the engine noise arriving at your eardrum never passes through the phone at all.
ANC is a property of the headset hardware. If you want quiet *at your ear*, that is an ANC
helmet headset or moulded earplugs, and the app cannot substitute for either.

What the app does instead is clean the voice **you transmit**, so your buddy hears you
rather than your bike. That is the half of the problem software can actually solve, and
on a motorcycle it is the half that decides whether the intercom is usable at speed.

### How it works

Three stages, cheapest first:

1. **150 Hz high-pass** in the capture path. Wind and engine rumble is mostly below this.
2. **The phone's own capture effects** — `NoiseSuppressor`, `AutomaticGainControl` and
   `AcousticEchoCanceler` — taken whenever the hardware offers them. They run in the audio
   HAL, cost no CPU, and are tuned by the manufacturer for that exact microphone.
   Availability varies; the settings screen shows what your phone actually has. (The
   realme RMX5264 used for testing offers all three.)
3. **Spectral suppression in software**, on whatever the first two left behind. A 512-point
   STFT with 50% overlap, a per-bin noise floor tracked by a fast-down/slow-up follower,
   and a spectral-subtraction gain per bin. Engine and wind are stationary across the
   window the follower watches, so they settle into the noise estimate; speech is transient
   and does not. Gains are smoothed across frames and floored rather than driven to zero,
   which is what stops suppression turning into warbling "musical noise".

Adds 32 ms of latency, against the 270-420 ms the A2DP leg already costs.

### Chopped voice — found on a road test, fixed

The first on-road test of suppression reported that it chopped the voice. Three things
compounded, and the first was a plain bug:

1. **The capture path measured its noise gate *after* denoising.** The denoiser pulls
   quiet speech — consonants, the tails of words — below the gate's threshold, and the
   gate then replaced those frames with digital silence. The gate now measures the signal
   before suppression, and does not run at all when the denoiser is on, because the
   spectral floor does the same job without a cliff edge.
2. **Suppression was stacking.** The test phone has a hardware `NoiseSuppressor`, which
   had already taken the steady noise out before the software pass saw it; subtracting
   that much again eats speech. The software depth is halved when hardware suppression is
   active.
3. **Strong was simply too aggressive.** The two knobs do different jobs — over-subtraction
   sets how much noise comes out, the gain floor sets how far any one bin may be pulled
   down and is what prevents chopping. The floor was raised from -24 dB to -19 dB and the
   depth retuned around it.

Two regression tests pin this: one asserts quiet speech is attenuated but never zeroed,
the other that the software pass backs off when the hardware is already suppressing.

### One subtlety worth knowing

A sustained, perfectly steady tone is — by any stationarity test — indistinguishable from
noise, so a naive denoiser slowly learns a held vowel and then subtracts the rider's own
voice away. This showed up immediately in testing. The fix is a frame-level speech-present
check: when the frame's total energy sits well above the learned floor, the per-bin
estimate is not allowed to rise at all. There is a test that holds a tone for five seconds
and asserts it does not fade.

If you ever hear your own voice thinning out when you hold a note, that guard is the thing
to look at.

### Capturing logs from a call

ColorOS rolls the log buffer over in about two minutes, which is shorter than a call, so
reading `logcat` afterwards loses the connect phase. Capture live instead:

```bash
./capture-call-log.sh rider-a
```

Start it before tapping Host or Join, Ctrl-C when the call ends. It writes to
`logs/`. Every 5 seconds the app emits one `IntercomStats` line with link state, RTT,
loss, jitter depth, mic level and VAD margin, which is the record of how the link
behaved when nobody was looking at the screen:

```bash
grep IntercomStats logs/rider-a-*.log
```

### What still needs two phones and a road

Nothing below has been run. These are the checks from the blueprint that a desk cannot
reproduce:

- [ ] RFCOMM connect and reconnect 50 times in a loop; record failures and exception text
- [ ] Kill one app by task swipe and from Settings; the other side should show the link
      lost within 3 s and recover within 10
- [ ] Music from Spotify, YouTube Music and a local player: ducks and restores, and the
      headset never switches to HFP
- [ ] One-way latency by clap test (budget is 270-420 ms, most of it the A2DP leg)
- [ ] Floor tie-break: both riders say "one" on a countdown; exactly one is heard
- [ ] Headset button: record the key codes each headset sends for single, double, triple
      and long press, using the calibration readout in Settings
- [ ] Ride at 40, 60, 80, 100 km/h with and without a wind blocker
- [ ] Separate to 10, 20, 30, 50 m and note where it drops
- [ ] Screen off for a whole ride, on each phone in the device matrix
- [ ] Take a real phone call mid-ride and confirm the intercom resumes
- [ ] One hour: battery drain per phone (target under 12%/hour)

Phase 1 passes when there are no manual reconnects in 30 minutes of town riding, speech
is intelligible at 60 km/h with a wind blocker, music stays stereo throughout, and drain
stays under 12% per hour.

### Known weak points before you ride

- **The headset button is the least deterministic part.** Android routes media keys to the
  last app that played audio locally, and there is no reliable way to take it from a
  playing music app. The app claims the session only around floor changes and hands it
  back. Test it on each headset before trusting it; the on-screen button and the
  notification actions always work.
- **A crashed service cannot restart itself.** Android 14 refuses to create a
  microphone-type foreground service from the background, so the app posts a notification
  rather than retrying — a silent retry is what produces one-way audio.
- **Budget headsets without multipoint** may drop off the phone when the intercom link
  starts. That is a chipset limit, not an app bug.
