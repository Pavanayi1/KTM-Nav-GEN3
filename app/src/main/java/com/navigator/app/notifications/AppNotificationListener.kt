package com.navigator.app.notifications

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.navigator.app.ble.BccuConnectionService
import com.navigator.app.ble.BccuProtocol
import com.navigator.app.logging.AppLogger
import com.navigator.app.settings.AppSettings

/**
 * Captures notifications from user-selected apps (WhatsApp/SMS/etc, see
 * AppSettings.notificationSourceApps) and mirrors them to the dash's
 * NOTIFICATION characteristic (the small bottom banner), plus separately
 * detects "which app is currently navigating" and mirrors that to the
 * dash's center guidance view (TURN_DISTANCE + TURN_ROAD - a different
 * display region entirely, confirmed from the official app's NavableBleImpl,
 * see BCCU_BLE_PROTOCOL.md) rather than the bottom banner.
 *
 * Scope note: turn-direction (left/right/straight) is derived via
 * TurnIconHeuristic - an explicitly unreliable image heuristic, not a
 * confirmed protocol field like everything else here. Included by request
 * despite that, since there's no text-based signal for turn direction in a
 * generic maps app's notification.
 */
class AppNotificationListener : NotificationListenerService() {

    private companion object {
        /** Next-turn distance beyond which the center view may show the current song instead of the road name. */
        const val FAR_TURN_METERS = 500.0
    }

    /**
     * Confirmed via a full hardware calibration pass (BCCU_BLE_PROTOCOL.md
     * section 7.2, 2026-07-05, "Symbol testing" screen): of all 8
     * NotificationIcon codes, only NOTIFICATION_REROUTING and
     * NOTIFICATION_WAYPOINT actually render on this Gen-3 dash's bottom
     * banner. Using WAYPOINT as the settled default for all app-generated
     * notifications.
     */
    private val dashIcon = BccuProtocol.NotificationIcon.NOTIFICATION_WAYPOINT

    /**
     * ONE settings instance for the life of the listener. Constructing
     * `AppSettings` is not cheap - it opens an EncryptedSharedPreferences
     * behind an Android Keystore round-trip - and this callback runs on the
     * main thread for EVERY notification on the phone, not just navigation
     * ones. Building a fresh one per callback was a multi-millisecond
     * main-thread stall every time anything on the phone posted a
     * notification.
     */
    private val settings: AppSettings by lazy { AppSettings(this) }

    /**
     * The system binds this listener for the life of the install, independent
     * of the connection service and of whether the app was ever opened - which
     * makes it the one component that can reliably notice "we have something
     * for the dash but nobody is connected to it" and put the service back up.
     */
    override fun onListenerConnected() {
        super.onListenerConnected()
        AppLogger.init(this)
        AppLogger.log("Notif", "Listener connected - making sure the bike service is up")
        BccuConnectionService.ensureRunning(this)
        com.navigator.app.connect.BikePresence.maybeEnsure(this, "listener connected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val packageName = sbn.packageName
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        AppLogger.log("Notif", "Posted: pkg=$packageName category=${sbn.notification.category} title=\"$title\"")

        // Any notification at all is a free wake-up for the one component of
        // ours the system keeps alive, so use it to ask whether the bike is
        // connected while we weren't looking. Throttled to once a minute and a
        // no-op while the service is up, so this is not the R65 revive loop:
        // that revived the service on ANY notification, whereas this only acts
        // when the bike is actually there.
        com.navigator.app.connect.BikePresence.maybeEnsure(this, "notification wake-up")

        // Dev-mode audit: record what was decided about every notification, so
        // the Developer screen can answer "why did/didn't that reach the dash".
        // A plain-prefs boolean read - cheap enough for this per-notification path.
        val auditing = settings.devModeEnabled
        fun audit(verdict: NotificationAudit.Verdict, reason: String) {
            if (!auditing) return
            NotificationAudit.record(
                NotificationAudit.Event(
                    timeMs = System.currentTimeMillis(),
                    packageName = packageName,
                    title = title,
                    text = text,
                    category = sbn.notification.category,
                    verdict = verdict,
                    reason = reason,
                )
            )
        }

        if (title.isBlank() && text.isBlank()) {
            audit(NotificationAudit.Verdict.SKIPPED, "Empty title and text")
            return
        }

        val isNav = isNavigationNotification(sbn, settings) && isLiveGuidance(sbn)

        if (isNav) {
            audit(NotificationAudit.Verdict.NAV, "Turn-by-turn guidance → dash center view")
            // Guidance has started/continued and it belongs on the dash: if the
            // service isn't up (idle shutdown, or the app was never opened this
            // boot) this is what brings it back, so the bike gets the same
            // directions in the background that it gets with the app open.
            BccuConnectionService.ensureRunning(this)
            val navText = if (title.isNotBlank()) "$title $text" else text
            // Distance and road line, from either Maps layout (issue #8: newer
            // Maps puts "400 m · Turn left onto X" all in the title).
            val fields = NavFields.split(
                title, text,
                extras.getCharSequence(NavFields.EXTRA_SHORT_CRITICAL_TEXT)?.toString(),
            )
            val distance = fields.distance
            val road = fields.road
            AppLogger.log("Notif", "Nav update from $packageName: title=\"$title\" text=\"$text\" -> distance=\"$distance\" road=\"$road\"")
            // Dump every extras key so we can see, from real logs, exactly which
            // field carries ETA/duration/remaining-distance - no guessing which
            // Android notification extra Google Maps uses for that subtitle line.
            // Gated: a nav notification carries 15-30 extras and is reposted
            // every second or two for the whole ride, so unconditionally this
            // was tens of log lines per second on the main thread. It's a
            // diagnostic, so it runs when the rider is actually diagnosing.
            if (AppLogger.fileLoggingEnabled) {
                for (key in extras.keySet()) {
                    AppLogger.log("Notif", "  extra[$key] = ${extras.get(key)}")
                }
            }
            NotificationRepository.updateNavText(packageName, navText)
            val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()
            val (etaText, remainingDistanceText) = parseEtaAndRemainingDistance(subText)
            // distance/road line up with the official app's own field split:
            // e.g. "110 m" (TURN_DISTANCE), "towards 1st Cross Rd" (TURN_ROAD).
            // Far-turn music: while the next turn is comfortably far away, the
            // road name adds nothing - show the current track there instead.
            // The countdown (TURN_DISTANCE) and ETA stay live, and the moment
            // the turn is < 500 m the next update reverts to the road name and
            // blanks the artist line (guidance alone never writes TURN_INFO).
            val track = NotificationRepository.currentTrack.value
            val farMeters = distance?.let { parseDistanceMeters(it) }
            if (settings.farTurnMusicEnabled && track != null && farMeters != null && farMeters >= FAR_TURN_METERS) {
                val (songTitle, artist) = com.navigator.app.dash.IdleDashboard.splitTrack(track)
                BccuConnectionService.sendDashTextIfRunning(
                    distance = distance,
                    info = artist,
                    road = songTitle,
                    eta = etaText,
                    remaining = remainingDistanceText,
                )
            } else {
                BccuConnectionService.sendDashTextIfRunning(
                    distance = distance,
                    info = "",
                    road = road,
                    eta = etaText,
                    remaining = remainingDistanceText,
                )
            }
            captureManeuverIconIfEnabled(sbn, title, text)
            val guessedIcon = TurnIconHeuristic.guessDirection(this, sbn)
            guessedIcon?.let { BccuConnectionService.sendTurnIconIfRunning(it) }
            // Stereo approach beeps: left ear = left turn, right ear = right turn,
            // faster pattern as the distance (the notification title) shrinks.
            // Ducked to a background hum while the bike is provably waiting
            // (GPS says stationary / vibration says the engine is off).
            if (settings.turnBeepEnabled) {
                com.navigator.app.audio.TurnBeeper.swapChannels = settings.swapBeepChannels
                com.navigator.app.audio.TurnBeeper.volumePercent = settings.beepVolumePercent
                com.navigator.app.audio.TurnBeeper.gpsSpeedKmh = com.navigator.app.location.SpeedMonitor.speedKmh.value
                com.navigator.app.audio.TurnBeeper.engineOn = com.navigator.app.sensors.VibrationMonitor.engineOn.value
                com.navigator.app.audio.TurnBeeper.onGuidance(distance, guessedIcon, road)
            }
            NotificationRepository.updateNavGuidance(
                NotificationRepository.NavGuidance(
                    distance = distance,
                    road = road,
                    eta = etaText,
                    remaining = remainingDistanceText,
                    maneuver = maneuverTextFor(guessedIcon),
                    turnIcon = guessedIcon,
                )
            )
            return
        }

        if (packageName !in settings.notificationSourceApps) {
            audit(NotificationAudit.Verdict.SKIPPED, "App not in the mirrored-apps list")
            return
        }

        // Only INDIVIDUAL conversations reach the dash: skip group chats
        // (WhatsApp groups, group MMS) and the per-app summary notifications
        // that just say "5 new messages".
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) {
            AppLogger.log("Notif", "Skipping group-summary notification from $packageName")
            audit(NotificationAudit.Verdict.SKIPPED, "Group-summary notification")
            return
        }
        if (extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false)) {
            AppLogger.log("Notif", "Skipping GROUP conversation from $packageName: \"$title\"")
            audit(NotificationAudit.Verdict.SKIPPED, "Group conversation")
            return
        }
        // WhatsApp fallback for older notification styles: group messages title
        // as "Group name: Sender" or carry "@ Group" in the conversation title.
        if (packageName == "com.whatsapp" &&
            (extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE) != null &&
                extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, true))
        ) {
            AppLogger.log("Notif", "Skipping WhatsApp group (conversation title present): \"$title\"")
            audit(NotificationAudit.Verdict.SKIPPED, "WhatsApp group (conversation title)")
            return
        }

        // Resolved here, not above: this is two binder calls and the nav path
        // (which fires every second or two for a whole ride) never used it.
        val appLabel = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
        } catch (e: Exception) {
            packageName
        }

        val combined = if (title.isNotBlank()) "$title: $text" else text
        AppLogger.log("Notif", "Captured from $packageName ($appLabel): $combined")
        NotificationRepository.addEntry(
            NotificationEntry(
                packageName = packageName,
                appLabel = appLabel,
                title = title,
                text = text,
                postTimeMs = sbn.postTime,
                isNavigation = false
            )
        )
        // Quick-mute: still record the notification in the in-app feed, but skip
        // pushing non-navigation notifications to the dash while mirroring is off.
        if (settings.mirrorEnabled) {
            BccuConnectionService.sendNotificationIfRunning(combined, dashIcon)
            audit(NotificationAudit.Verdict.MIRRORED, "Sent to dash banner")
        } else {
            audit(NotificationAudit.Verdict.CAPTURED, "Mirroring off - kept in-app only")
        }
    }

    /**
     * Best-effort extraction of ETA and remaining distance from a nav app's
     * notification subtitle line, e.g. "22 min - 9.7 km - 2:12 am ETA" (the
     * "Maps - ... - Now" wrapper around it is added by Android's system UI,
     * not part of the app's own extras). This is standard Android notification
     * API usage (EXTRA_SUB_TEXT), not reverse-engineered BLE protocol - but the
     * exact subtitle format is still only as reliable as what real logs show,
     * so this returns null for either field rather than guess if the segment
     * doesn't look like a time or a distance.
     *
     * ETA is reformatted to plain zero-padded "HH:MM" with no am/pm suffix -
     * the official app's own confirmed example (Eta.java: "12:45") has no
     * am/pm text, and a real-hardware test sending "2:12 am" wrote successfully
     * over BLE but never rendered, while the original working screenshot for
     * this same dash slot showed a bare "00:00" - strongly suggesting the
     * firmware expects a strict numeric time, not extra letters.
     */
    /**
     * Best-effort meters from a nav notification's distance text ("110 m",
     * "1.2 km", "0.5 mi"). Null when the text doesn't look like a distance -
     * far-turn substitution then stays off, which is the safe default.
     */
    private fun parseDistanceMeters(text: String): Double? {
        val m = Regex("([0-9]+(?:[.,][0-9]+)?)\\s*(km|mi|ft|m)\\b", RegexOption.IGNORE_CASE).find(text) ?: return null
        val value = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        return when (m.groupValues[2].lowercase()) {
            "km" -> value * 1000.0
            "mi" -> value * 1609.34
            "ft" -> value * 0.3048
            else -> value
        }
    }

    private fun parseEtaAndRemainingDistance(subText: String): Pair<String?, String?> {
        if (subText.isBlank()) return null to null
        // Nav apps use a variety of separators between the "22 min", "9.7 km"
        // and "2:12 ETA" segments: middle dot, bullet, hyphen, en/em dashes.
        val segments = subText.split("·", "•", "-", "–", "—", "|").map { it.trim() }.filter { it.isNotEmpty() }
        val timePattern = Regex("""(\d{1,2}):(\d{2})\s*(am|pm)?""", RegexOption.IGNORE_CASE)
        val distancePattern = Regex("""\d+(\.\d+)?\s*(km|mi|m)\b""", RegexOption.IGNORE_CASE)
        var eta: String? = null
        var distance: String? = null
        for (segment in segments) {
            if (eta == null) {
                val match = timePattern.find(segment)
                if (match != null) {
                    var hour = match.groupValues[1].toInt()
                    val minute = match.groupValues[2]
                    val meridiem = match.groupValues[3].lowercase()
                    if (meridiem == "pm" && hour < 12) hour += 12
                    if (meridiem == "am" && hour == 12) hour = 0
                    eta = "%02d:%s".format(hour, minute)
                }
            }
            if (distance == null && distancePattern.matches(segment)) {
                distance = segment
            }
        }
        return eta to distance
    }

    /** Best-effort maneuver label for the Direction screen, derived from the guessed turn icon. */
    private fun maneuverTextFor(icon: com.navigator.app.ble.BccuProtocol.TurnIcon?): String? {
        val name = icon?.name ?: return null
        return when {
            name.contains("LEFT") -> "Turn left onto"
            name.contains("RIGHT") -> "Turn right onto"
            name.contains("STRAIGHT") || name.contains("CONTINUE") -> "Continue onto"
            name.contains("UTURN") || name.contains("U_TURN") -> "Make a U-turn onto"
            else -> null
        }
    }

    /**
     * When the navigation app's own notification goes away (rider exited Maps,
     * arrived, or stopped guidance), tell the dash to clear its center guidance
     * view. Without this the last turn stayed frozen on the dash. The service
     * debounces the actual clear, so Maps' routine remove+repost during a route
     * doesn't cause a flicker.
     */
    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (isNavigationNotification(sbn, settings)) {
            AppLogger.log("Notif", "Nav notification removed from ${sbn.packageName} - scheduling guidance clear")
            // TurnBeeper.reset() deliberately NOT called here: Maps routinely
            // removes+reposts its notification mid-route, and resetting the beep
            // dedup on every raw removal made the same turn beep again on each
            // repost. The service's debounced clearGuidance() resets it instead,
            // only when navigation has genuinely ended.
            BccuConnectionService.clearGuidanceIfRunning()
            NotificationRepository.clearNav()
        }
    }

    /**
     * Debug maneuver-icon harvester, OFF unless a marker file
     * `files/CAPTURE_MANEUVERS` exists (create it with adb during an emulator
     * session; it never exists for real users). Saves each distinct maneuver
     * largeIcon to `files/maps_capture/<label>__<hash>.png`, where <label> is the
     * nav app's own instruction text - giving a ground-truth-labeled bitmap set
     * to validate the text parser and the geometric fallback against real Maps.
     */
    private fun captureManeuverIconIfEnabled(sbn: StatusBarNotification, title: String, text: String) {
        try {
            val dir = filesDir
            val marker = java.io.File(dir, "CAPTURE_MANEUVERS")
            if (!marker.exists()) return
            val bitmap = TurnIconHeuristic.loadManeuverBitmap(this, sbn) ?: return
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val hash = pixels.contentHashCode()
            val label = (text.ifBlank { title }).lowercase()
                .replace(Regex("[^a-z0-9]+"), "_").trim('_').take(40).ifBlank { "unlabeled" }
            val outDir = java.io.File(dir, "maps_capture").apply { mkdirs() }
            val file = java.io.File(outDir, "${label}__${hash}.png")
            if (file.exists()) return
            java.io.FileOutputStream(file).use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            AppLogger.log("Capture", "Saved maneuver icon: text=\"$text\" hash=$hash -> ${file.name}")
        } catch (e: Exception) {
            AppLogger.log("Capture", "!! capture failed: $e")
        }
    }

    /**
     * A nav app posts far more than guidance: traffic-incident reports,
     * "rate this place", offers. Those were all being driven onto the dash as
     * if they were turn-by-turn, which is how a red incident badge ended up
     * being read as a maneuver icon.
     *
     * Live guidance is ongoing - it sits in the shade for the whole route - or
     * the app tags it CATEGORY_NAVIGATION, which Google Maps does. Either is
     * enough; requiring both is what silently dropped real guidance from apps
     * that don't set the category.
     */
    private fun isLiveGuidance(sbn: StatusBarNotification): Boolean =
        sbn.isOngoing || sbn.notification.category == Notification.CATEGORY_NAVIGATION

    private fun isNavigationNotification(sbn: StatusBarNotification, settings: AppSettings): Boolean {
        val override = settings.navAppOverride
        if (override != null) {
            return sbn.packageName == override
        }
        // Any notification from a known nav app counts, not just ones tagged
        // CATEGORY_NAVIGATION - several maps apps don't reliably set that
        // category on their turn-by-turn notification, so requiring both
        // was silently dropping real navigation updates.
        return sbn.packageName in AppSettings.KNOWN_NAV_APPS
    }
}
