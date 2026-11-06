package com.navigator.app.connect

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.SystemClock
import com.navigator.app.ble.BccuConnectionService
import com.navigator.app.logging.AppLogger
import com.navigator.app.settings.AppSettings

/**
 * "Is the bike connected right now?" - asked rather than waited for.
 *
 * [AutoConnectReceiver] is edge-triggered: it only ever learns about the bike
 * from an ACL_CONNECTED broadcast arriving. Miss that single edge - the app was
 * in the stopped state, Doze or the OEM dropped the broadcast, or the Classic
 * media link came up before our receiver was live - and nothing ever notices
 * that the bike has been sitting there connected the whole time. Nothing in the
 * app asked; it only ever listened.
 *
 * This is the level-triggered counterpart. It looks at what is connected right
 * now and starts the service if the bike is there, so a missed edge costs one
 * check interval instead of the whole ride.
 *
 * It runs from [com.navigator.app.notifications.AppNotificationListener],
 * deliberately: the listener is bound by the system and is the one component
 * that stays alive when everything else of ours is gone - which is exactly the
 * situation this exists to recover from.
 */
@SuppressLint("MissingPermission") // the BLE permissions are checked before pairing is ever saved
object BikePresence {

    /** A check costs a couple of binder calls, and notifications can arrive in bursts. */
    private const val THROTTLE_MS = 60_000L

    @Volatile private var lastCheckMs = 0L

    /**
     * Cheap no-op when the service is already up. Otherwise, at most once per
     * [THROTTLE_MS], look for the bike among the currently-connected devices.
     */
    fun maybeEnsure(context: Context, reason: String) {
        if (BccuConnectionService.isRunning) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastCheckMs < THROTTLE_MS) return
        lastCheckMs = now
        runCatching { check(context, reason) }
            .onFailure { AppLogger.log("AutoConnect", "!! presence check failed: $it") }
    }

    private fun check(context: Context, reason: String) {
        val settings = AppSettings(context)
        val savedAddress = settings.bondedDeviceAddress ?: return
        val savedName = settings.bondedDeviceName
        val manager = context.getSystemService(BluetoothManager::class.java) ?: return
        val adapter = manager.adapter ?: return
        if (!adapter.isEnabled) return

        // GATT lists come back synchronously.
        val direct = mutableSetOf<BluetoothDevice>()
        runCatching { direct += manager.getConnectedDevices(BluetoothProfile.GATT) }
        runCatching { direct += manager.getConnectedDevices(BluetoothProfile.GATT_SERVER) }
        startIfBikePresent(context, direct, savedAddress, savedName, reason)

        // The dash's Classic media link lives on the audio profiles, and those
        // need a proxy that arrives asynchronously. This is the link that is up
        // whenever the ignition is on, so it is the one most worth asking about.
        for (profile in intArrayOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET)) {
            runCatching {
                adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(which: Int, proxy: BluetoothProfile) {
                        runCatching {
                            startIfBikePresent(
                                context, proxy.connectedDevices.toSet(),
                                savedAddress, savedName, "$reason/profile$which",
                            )
                        }
                        runCatching { adapter.closeProfileProxy(which, proxy) }
                    }

                    override fun onServiceDisconnected(which: Int) = Unit
                }, profile)
            }
        }
    }

    private fun startIfBikePresent(
        context: Context,
        devices: Set<BluetoothDevice>,
        savedAddress: String,
        savedName: String?,
        reason: String,
    ) {
        // Re-checked here, not just in maybeEnsure: the profile proxies answer
        // asynchronously, and an earlier branch may already have started it.
        if (BccuConnectionService.isRunning) return
        val bike = devices.firstOrNull { device ->
            val seenName = runCatching { device.name }.getOrNull()
            device.address.equals(savedAddress, ignoreCase = true) || namesMatch(savedName, seenName)
        } ?: return
        val seenName = runCatching { bike.name }.getOrNull() ?: savedName ?: bike.address
        AppLogger.log(
            "AutoConnect",
            "Bike \"$seenName\" is connected over Bluetooth but the service was down - starting it [$reason]",
        )
        // Same rule as ACL_CONNECTED: the bike being connected means the rider
        // is riding, which overrides an earlier Stop.
        AppSettings(context).userQuit = false
        try {
            BccuConnectionService.start(context, savedAddress)
        } catch (e: Exception) {
            AppLogger.log("AutoConnect", "!! background start refused: $e")
        }
    }

    /**
     * True when two dash names are the same once spacing, separators and case
     * are ignored - the cluster advertises "KTM3638" over BLE but a Classic
     * media link can report it spaced or cased differently. Both names have to
     * be present: two unnamed devices are not a match, which a plain
     * empty-string compare would have called one.
     */
    fun namesMatch(saved: String?, seen: String?): Boolean {
        val a = normalise(saved) ?: return false
        val b = normalise(seen) ?: return false
        return a == b
    }

    private fun normalise(name: String?): String? = name
        ?.filterNot { it.isWhitespace() || it == '-' || it == '_' }
        ?.lowercase()
        ?.takeIf { it.isNotEmpty() }
}
