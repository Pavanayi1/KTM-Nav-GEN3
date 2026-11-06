# Changelog

## R76 (2026-11-07)

**Rider Intercom: talk to your pillion.** A phone-to-phone Bluetooth intercom is now built in. Open it
from the new **INTERCOM** home tile; your pillion uses the small companion app from the same release.
Voice rides on the media channel so music keeps playing. Pick **Intercom** in the handlebar mode picker
and **hold SET to talk**, UP/DOWN for volume, BACK for noise suppression, BACK twice to end the call. The
mic always closes on release, on a dash disconnect, on call end, or after 60 s. Not yet road-tested.

**The dash no longer sits on the chequered flag.** A rider saw the "destination reached" flag for a whole
ride: words like "arrive at 5:40 PM" in the ETA line were being read as arrival. Arrival now needs a real
arrival phrase, and on arrival the dash shows a one-time **Location reached** banner.

**Long text scrolls everywhere.** Road names and instructions too long for a dash slot now scroll
(marquee) instead of being cut off.

**Home and Work on the dash's own navigation menu.** Set Home and Work in Settings → Dash favourites,
then pick one from the bike's own menu with the handlebar and Google Maps starts the route.

**Connects when the bike does.** If your phone's Bluetooth is already connected to the bike (calls,
music) the app now starts itself and links the dash, instead of only beeping turns. Optional **Keep
session alive** in Settings → Connection keeps it linked between stops.

**Fewer wrong arrows.** Street-view photos and incident badges in Google Maps notifications are no longer
mistaken for turn arrows.

**Home screen.** Tiles light up and glow only while something is live (notifications, a route, music, a
call) and are plain boxes otherwise. The connection bar animates while connecting. Quick toggles are
green when on. Scrolling the tiles folds the toggles away to make room. The on-screen D-pad (the + in the
corner) is gone: the handlebar, touch and the back gesture cover every screen.

## Unreleased (R29–R33)

**The handlebar remote is now a proper controller.** Long-press UP (from any
screen) opens a mode picker — **Controller**, **Music**, or **Bike (off)** — so
you choose what the buttons do, and "off" hands them back to the bike as normal.
- **Controller:** UP/DOWN and left/right act as a D-pad over whatever's on
  screen, double-click SET to select (it now reliably *opens* apps, not just
  highlights them), long-press SET to long-click an item, double-click BACK to go
  back. Great for driving other apps from the bars.
- **Music:** single UP/DOWN change volume, double UP/DOWN skip to the next or
  previous track, SET plays/pauses.
- **Long-press DOWN opens Google Maps** from anywhere.
- Every press registers cleanly on release, and the app tells a quick tap, a
  double-click and a press-and-hold apart — tuned from real measurements on the
  bike.

**Now-playing on the dash.** When the song changes, the track scrolls across the
dash's bottom bar once — e.g. "Adventure Of A Lifetime by Coldplay".

**Cleaner messages on the dash.** Mirrored messages and notifications are now
stripped of emoji and symbols the dash can't display, so they read as clean text
instead of boxes.

**Keys survive a reinstall too.** On top of surviving updates, your pairing keys
are now included in a scoped backup, so a reinstall or a new phone won't force
you to re-pair (your private keys like the AI summary key are deliberately left
out of that backup).

## Unreleased (R28)

**No more re-pairing on every ride (fixed).** The big one. Until now, every time
you switched the ignition off and on, the dash would ask you to "add device"
again before navigation would reconnect — because the app wasn't remembering the
security keys from your first pairing, so the dash saw your phone as brand new
each time. The app now saves those keys and presents them on reconnect, so the
dash recognises your phone and links back up **silently, with nothing to tap**.
Verified on a real bike: after one final pairing, an ignition off/on now
reconnects in about a second with no prompt on the dash.

Note: the very first reconnect after updating will still ask once (that's when
the app learns and stores the keys). Every ride after that is automatic.

## Unreleased (R25)

**Automatic bike detection.** The app now recognises which dash it is talking to
on its own. As soon as it connects it reads the dash's model, firmware and the
set of features it exposes, and shows the detected type right in the connection
banner (for example "Navigator Gen 3 (BCCU)"). There is nothing to configure -
it just identifies your bike.

**Better handling of unsupported bikes.** Previously, if you connected to a dash
that isn't a Gen-3 BCCU unit, the app would sit on "handshaking" forever with no
explanation. It now tells you plainly that the dash isn't supported and records a
full diagnostic log of exactly what that bike exposes. If you have a bike that
won't pair (some 1290 / 890 reports), please connect once and send the log - that
is what lets us add your model.

**Reconnect fix.** The saved bike name now refreshes itself every time you
connect, so it can no longer get stuck showing the wrong name (e.g. a headset)
after an update.

**A note on older models.** People have asked about support for older KTMs. Those
bikes (the earlier "MY RIDE" system) don't use the same Bluetooth technology as
Gen-3 at all - they use an older Bluetooth Classic connection with a completely
different message format. Supporting them means building a whole second
connection path, which is planned but not in this build. R25 puts the
bike-detection groundwork in place so the app can tell the two apart and route
each correctly once that path exists.

## v0.2.0-beta - Navigator Gen3 (formerly OpenDash)

This is a large update over the first public release (v0.1.0), and it is a beta. Please expect a few
rough edges and send logs if something misbehaves.

### About the name

The app is now called Navigator Gen3 (it was OpenDash at v0.1.0). The old name overlapped with
existing projects, which was going to cause confusion. Thanks to u/SubtleAsFucc on Reddit for
flagging it early. Same app, same maintainer, same on-device, no-account approach - just a clearer
name.

### New since v0.1.0

**Husqvarna support (new).** This is the big one. v0.1.0 was KTM only. Navigator Gen3 now also works
with Husqvarna Gen-3 models, which share the same dash electronics, so the same navigation,
notifications, sounds and ride features all work. The app themes itself to match your bike - KTM in
the dark orange look, Husqvarna in a lighter blue look - picked on first run and switchable any time
from Settings.

**Sound.**
- Stereo turn-approach beeps: a beep in your left ear for a left turn, your right ear for a right
  turn, with the tempo tightening as you close in on the corner.
- The beeps drop to a quiet hum when you are stopped (at a light, or with the kill switch off) and
  come back the moment you move off, so they do not nag while you wait.
- Adjustable beep volume, a mellower tone, and a left/right channel swap for headsets that are wired
  the other way.
- A much louder overspeed alarm - it now plays on the alarm audio channel at full volume and sounds
  through Do Not Disturb.
- You can preview the beeps and the overspeed alarm from Settings before you ride.

**Ride recording and the ride viewer (GPX).**
- Optional automatic ride recording to a standard GPX track. It is power-aware: full detail on the
  bike's charger, gentle on the battery otherwise, and it can be turned off entirely.
- A new Rides screen draws each ride as a map coloured by speed (fast in red, mid-range in green,
  easy cruising in blue, crawling in near-black), plus a speed-over-time graph and ride stats.
- A rough traffic readout: when you are stopped with the engine running it is marked as traffic; with
  the engine off it is treated as an intentional stop, so the summary shows time lost to traffic.
- You can open a .gpx file from any other app to view it here, and share your recorded rides out.

**Engine detection.** The app can tell whether the engine is actually running from the phone's motion
sensor, after a short guided calibration. This is what lets the beeps go quiet at a light and lets
the ride viewer tell traffic from a coffee stop.

**Handlebar remote and notifications.**
- The handlebar remote now has selectable modes - media control, phone gamepad, or the app's own
  menu - switched with a triple-press of Up, and the gamepad highlights the actual buttons more
  reliably.
- Group chats and "5 new messages" summaries are filtered out, so only real, individual messages
  reach the dash.
- The persistent notification has an Exit action that genuinely stops the app.

**Reconnection.** The reconnect logic was reworked: it waits for the dash to finish waking after the
ignition comes on, then backs off instead of hammering the link, which cuts down the repeated
re-pair prompts. It is better, but not fully solved - see Known issues.

**Setup and stability.**
- Reworked first-run walkthrough with a "skip all" option.
- Your paired bike and brand choice are now stored somewhere that survives app updates, so you should
  not get thrown back to the pairing screen after every install.

**Turn icons.** The on-device turn-icon recognition works as it did in v0.1.0 (same model). The
training dataset is now included in the repo for anyone who wants to help improve it.

### Known issues

1. Bluetooth auto-connect is not working reliably. After you cycle the ignition off and on, the app
   does not silently reconnect on its own. When it does reconnect, you currently have to accept the
   connection on the bike's dash each time. This is the top thing being worked on.
2. First pairing still needs the physical "add device" confirmation on the dash. Expected, once per
   bike.
3. Turn-icon accuracy depends on your Google Maps version and your specific dash. Use the Symbol Test
   and Turn-icon Calibration screens if an icon looks wrong.
4. Husqvarna support has not yet been verified on a physical Husqvarna. It should work on the shared
   dash protocol, but testing so far has only been on a KTM.
5. Engine detection needs calibration and varies by phone and mount.
6. This is a beta - if anything crashes or misbehaves, please open an issue or share your logs.

---

## v0.1.0 - OpenDash

First public release. Google Maps turn-by-turn mirrored to the KTM Gen-3 dash, on-device maneuver-
icon recognition, notification mirroring, handlebar remote as a gamepad, and symbol/turn-icon
calibration. KTM only.
