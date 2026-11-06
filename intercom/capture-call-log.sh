#!/usr/bin/env bash
# Capture a clean log of one intercom call.
#
# Run this BEFORE you tap Host or Join, leave it running for the whole call, then
# Ctrl-C. The phone's log buffer rolls over in a couple of minutes under ColorOS, so
# reading logcat after the fact loses the connect phase — this writes to disk live.
#
#   ./capture-call-log.sh            # capture to logs/call-<timestamp>.log
#   ./capture-call-log.sh rider-a    # capture to logs/rider-a-<timestamp>.log
set -euo pipefail

ADB="${ADB:-adb}"
NAME="${1:-call}"
STAMP="$(date +%Y%m%d-%H%M%S)"
OUT="logs/${NAME}-${STAMP}.log"
mkdir -p logs

"$ADB" logcat -G 16M >/dev/null 2>&1 || true
"$ADB" logcat -c

echo "Recording to $OUT"
echo "Start the call now. Ctrl-C when it is over."
echo

# Our tags plus the system's audio routing and Bluetooth decisions, which are what
# tell you whether the headset stayed on A2DP.
"$ADB" logcat -v time \
    RiderIntercom:V IntercomStats:V IntercomService:V CallController:V \
    RfcommLink:V FloorArbiter:V MicCapture:V VoicePlayer:V FocusManager:V \
    ButtonSession:V AudioRecord:D AudioTrack:D BluetoothSocket:D \
    MediaFocusControl:I AudioService:I A2dpService:I HeadsetService:I \
    AndroidRuntime:E \
    '*:S' | tee "$OUT"
