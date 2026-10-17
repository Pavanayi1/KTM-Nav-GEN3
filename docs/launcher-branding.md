# Launcher branding

Confirming the existing brand selector also changes the Android launcher entry:

| Selection | Name | Icon |
| --- | --- | --- |
| No saved brand | Navigator Gen3 | Original orange navigation arrow |
| KTM | KTM Navigator | Original orange navigation arrow |
| Husqvarna | Husqvarna Navigator | White navigation arrow on theme blue (#2C5CB0) |

The default alias uses the base appLabel build placeholder. Branded debug labels
append Test when husqvarnaTestBuild=true, for compatibility with the separately
installable field-test build proposed in the connection contribution. This PR
alone does not change the application ID or add that separate installation.

Tapping a brand chip previews the in-app theme; confirming applies the launcher
change. Back cancels the preview. Existing users get their saved brand on the
first launch after updating. Changing brands does not change Bluetooth behavior,
saved bikes, keys, permissions or app data.

## Implementation

Three stable activity aliases target the always-enabled MainActivity. Only the
default alias is initially enabled. Android 13+ changes aliases atomically;
Android 8–12 enables the replacement before disabling previous entries. All
changes use DONT_KILL_APP. Failure stops the switch, is logged, and is retried
from the saved brand next launch. The foreground notification targets MainActivity
directly so an existing notification still opens after its old alias is disabled.
GPX intent filters remain on MainActivity. Alias names include the application ID
to avoid assuming a fixed installed package name.

This changes the **launcher** name and icon. Android Settings, permission screens
and the foreground notification retain the app's base identity. Launcher refresh
timing and pinned shortcut handling depend on the phone's launcher; a replaced
home-screen shortcut may need to be added again from the app drawer. With Android
themed icons enabled, both brands use the same monochrome navigation arrow and
the launcher's palette, so their brand colors are not shown.

References: [Android activity aliases](https://developer.android.com/guide/topics/manifest/activity-alias-element)
and [atomic component updates](https://developer.android.com/reference/android/content/pm/PackageManager#setComponentEnabledSettings(java.util.List)).

## Validation

JVM tests cover every enabled-alias combination, both brand directions, retaining
at least one entry point during sequential updates, recovery from interrupted
switches and no-op subsequent launches. Builds must verify the application ID,
labels, alias targets and initial enabled states in the merged manifest.

A combined field build on Xiaomi 15 / HyperOS 3 logged a successful switch to the
Husqvarna alias, and subsequently connected to the motorcycle and displayed navigation.
The log confirms that Android accepted the alias update; it does not verify the
home-screen appearance, switching in both directions or shortcut behavior.

Still to check on a phone (including HyperOS 3):

1. Fresh install: one default entry; confirm KTM, then Husqvarna, then KTM again.
2. Close/reopen and reboot: the chosen entry launches and keeps its brand.
3. Preview another brand and go back: launcher identity stays unchanged.
4. Update from a build with a saved brand, and from this build with each alias
   enabled: the app stays accessible and no duplicate entry remains after launch.
5. Switch while the connection notification exists, then tap that notification;
   also open a GPX file. Both routes must still open the app.

No phone/launcher behavior is claimed verified by JVM tests.
