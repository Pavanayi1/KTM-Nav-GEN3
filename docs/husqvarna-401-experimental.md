# Experimental Husqvarna connection profile

Status: **authentication, key reuse and visible navigation field-tested on one bike**.
Evidence comes from a Svartpilen 401 (2025), with a Xiaomi 15 running HyperOS 3.
The app's visual brand selection does not select a Bluetooth protocol.

## Field result — 2026-10-13

Test setup: Husqvarna Svartpilen 401 (2025), Xiaomi 15 / HyperOS 3, ignition on
and engine off. The optional experimental profile was selected explicitly.

An initial attempt reached key selection but paused with MISSING_KEYS. After a
fresh pairing procedure, the recorded sequence was:

- 13:25:56: GENERATE_KEYS followed by AUTHENTICATED; pairing state was persisted.
- 13:28:39: the saved pool of 16 keys was restored and authentication completed.
- 13:31:54: a second connection restored the saved keys and authenticated again.

These are local log timestamps. The rider also confirmed that navigation arrows
and distances appeared on the dash. Notification text rendered, but some notification
icons were absent or different; the existing Works/Wrong test does not distinguish
those cases. This is not a claim that every icon is supported.

The later connections follow service restarts in the log. Ignition-cycle behavior,
process-death recovery and KTM hardware regression remain separate, unverified tests.
The final connection attempt in the capture ends before an authentication result.
No telemetry was obtained; the discovered table did not include PRPC service 0600.
The field APK also included cosmetic launcher changes submitted separately; no
Bluetooth behavior depends on those changes.

## Problem and evidence

With Navigator's existing first-connection behavior, this dash sent HELLO, received
a reply with command 0 and closed the BLE link. A second test alternated successful
Ride Husqvarna sessions with Navigator attempts after force-stopping Ride Husqvarna.
Nine Navigator attempts ended 70–162 ms after the initial reply. Each write received
an ATT acknowledgement, followed by a remote disconnect (HCI reason 19).

The successful official sessions used:

| Stage | Observed behavior |
|---|---|
| Initial exchange | Same nonce mixing and AES-CBC control framing as BccuCrypto |
| Reply to HELLO | Command 1, rather than Navigator's command 0 |
| AUTH_REPLY writes | ATT Write Command (without response) |
| Initial authorization in the first capture | Generate keys, acknowledge 2, select a key |
| Later official reconnections | Select an existing key without generating a new pool |

AUTH_REPLY advertised support for both write types. Its use of write-with-response
is therefore a difference to test, **not independently proven to cause the failure**.
The experimental profile changes both observed auth behaviors; further field testing
must isolate their effects if necessary.

The existing crypto algorithm decoded 30 official navigation frames in the first
capture and 59 in two later reconnects. The later frames used a key from the earlier
pool and a new per-connection IV. This supports persistent per-bike keys; it does not
give Navigator access to the official app's stored keys.

## Implementation boundaries

- Standard is the default for every device, including missing/unknown profile IDs.
  It keeps HELLO reply 0, existing with-response writes, key loading and retry policy.
- The experimental profile is saved by device address and never inferred from a
  device name, a selected theme or a service UUID shared with KTM.
- Only experimental AUTH_REPLY writes prefer no-response, and only when the
  characteristic advertises support. Descriptors and other characteristic writes
  retain their existing behavior.
- Both modes use the existing serial GATT queue and Android write callbacks. Late
  callbacks from a closed GATT client are ignored, including during manual restart.
- The experimental profile validates a stored pool as exactly 16 keys of 16 bytes.
  Missing/malformed keys stop authentication before key acknowledgement or navigation.
- After three remote disconnects following HELLO, or a handshake timeout, experimental
  retries pause. The reason survives service/process restart. Automatic starts and
  Bluetooth events cannot clear it; an explicit Retry or Save and reconnect can.
- The app does not automatically delete bonds, erase keys, import keys from a capture,
  or guess undocumented reset commands. New keys still have to come from the dash's
  GENERATE_KEYS exchange.

The source currently published on the repository differs from the v0.3.0 release APK.
This change is based on the repository source, not a reconstructed release APK. No
claim is made that it includes every feature of the release binary.

## Enable and test

1. Stop Ride Husqvarna and other Navigator installations so only the test app connects.
2. In the device list, select **Connection options** below the intended bike; alternatively
   use **Settings → Connection options** for the saved bike.
3. Select **Husqvarna 401 (2025) — experimental**, then **Save and reconnect**.
4. Record the visible result and export the app log. Test with the bike stationary.

If the app reports missing keys, the dash requested a pool that this installation
has never generated. Android's existing Bluetooth bond is not enough. A separate
fresh-authorization test is needed. For the successful field test, the rider was
instructed to stop the official app and other Navigator installations, remove the
phone/bike pairing on both sides, use the dash's add-phone menu and the phone's
Bluetooth settings to pair, then open Navigator and select Save and reconnect
with the experimental profile. Accept any phone/dash prompts. Navigator currently
does not request phone discoverability itself. Preserve the app's data and any
existing keys; deleting app data is not part of this procedure.
A fresh Android bond alone may
still not trigger GENERATE_KEYS on every firmware. Stop and report that result
instead of repeatedly deleting bonds or assuming success.

An authenticated state means Navigator selected a locally available key; verify an
actual indication on the dash before reporting navigation compatibility. Well-formed
but stale stored keys cannot be detected from their length alone.

### Field-test matrix

- [ ] KTM / Standard: first pairing, reconnect after ignition cycle, navigation and buttons.
- [ ] KTM / Standard: existing saved key pool and normal reconnect/backoff behavior.
- [x] Husqvarna experimental: initial authorization produces and persists fresh keys.
- [x] Husqvarna experimental: navigation indication actually appears on the dash (rider confirmed).
- [x] Husqvarna experimental: two later connections reuse persisted keys after service restart.
- [ ] Husqvarna experimental: reconnect after cycling ignition or OS process termination.
- [ ] Husqvarna experimental: missing keys show a reason; no authentication or retry loop.
- [ ] Husqvarna experimental: after three post-HELLO rejections, retries remain paused
      across app restart and Bluetooth off/on until explicit retry.
- [ ] Two saved bikes: changing one profile does not change the other.
- [ ] Switching the same bike back to Standard restores its original protocol policy.
- [ ] Music/intercom and handlebar controls continue to work.

## Automated checks and builds

Requires JDK 17 and Android SDK 34; Gradle wrapper is included.

```sh
./gradlew testDebugUnitTest assembleDebug lintDebug
```

Tests exercise default/unknown profiles, write-type scoping and capability fallback,
missing/malformed key pools, the experimental retry budget and KTM's unchanged retry
policy. An independently generated synthetic AES/SHA-512 vector exercises nonce
mixing, control decoding, key derivation and navigation decoding. Unit tests cannot
validate a physical dash or Android vendor-specific Bluetooth behavior.

Local validation: both normal and experimental APK builds compile and the 14 unit
tests pass. Full lint is
not green on the upstream source: an unmodified checkout
of `ceafaf1fa899cdbb646051f1f517372eb21759fa` reports the same three errors in
`CallAudioRouter` (Bluetooth permission), `RouteRecorder` (location permission) and
`AppLogger` (API 29 Downloads URI with minSdk 26). Those unrelated source files and
lint severities are unchanged by this contribution.

For a separately installable diagnostic APK:

```sh
./gradlew -PhusqvarnaTestBuild=true testDebugUnitTest assembleDebug lintDebug
```

This optional debug build uses application ID `com.navigator.app.husqvarnaexperimental`
and label **Navigator Husqvarna Test**. It has separate settings/keys and does not
replace the released app. It must receive its own permissions and authorization.
Its default connection profile remains Standard until explicitly selected.
Normal debug/release package IDs and labels are unchanged. FileProvider authorities
follow the application ID so the two installations do not collide.

## Reporting results

Include model/year, phone/Android version, selected profile, whether keys were newly
generated or restored, and the last auth stage before disconnect. The contribution
contains no raw bugreports, Bluetooth addresses, VINs, route text or captured keys.
Share only sanitized excerpts when opening a public issue or pull request.
