package com.mensa.riderintercom.transport

import com.mensa.riderintercom.util.TransportPreference

/** Why the selector decided to move to another radio. Shown to the rider and logged. */
enum class SwitchReason(val explain: String) {
    Loss("frame loss stayed high"),
    Unreachable("the link stayed down"),
    GroupTimeout("Wi-Fi Direct could not form a group"),
    Preference("the rider's transport preference"),
}

data class SwitchDecision(val target: Transport, val reason: SwitchReason)

/**
 * Decides *when* to change radio. Deliberately free of Android types so the rules can be
 * tested directly rather than inferred from a road test.
 *
 * The rules, in the order they fire:
 *
 *  1. **Loss** — connected, but frame loss has stayed above [lossThresholdPercent] for a
 *     continuous [lossWindowMs]. A brief spike behind a lorry is not a reason to move.
 *  2. **Unreachable** — the link has not been connected for [unreachableMs], which covers
 *     a reconnect loop that has run out of road (1+2+4+8+8 s of backoff is about 20 s).
 *  3. **Group timeout** — Wi-Fi Direct has had [groupFormMs] to form a group and has not,
 *     so go back to Bluetooth rather than leave the rider with nothing.
 *
 * Two guards stop it oscillating: a minimum dwell on each transport, and a cap on how
 * many times one session may switch at all. A rider on a bad stretch of road should not
 * have the app flapping between radios every twenty seconds.
 */
class SwitchPolicy(
    private val preference: TransportPreference,
    private val lossThresholdPercent: Double = 5.0,
    private val lossWindowMs: Long = 10_000,
    private val unreachableMs: Long = 20_000,
    private val groupFormMs: Long = 30_000,
    private val minDwellMs: Long = 20_000,
    private val maxSwitches: Int = 6,
) {
    private var lossHighSince: Long = NEVER
    private var disconnectedSince: Long = NEVER
    private var enteredCurrentAt: Long = 0
    private var lastSwitchAt: Long = NEVER
    private var everConnectedOnCurrent = false

    var switches: Int = 0
        private set

    /** Which radio to open first. Range mode starts on Wi-Fi Direct; everything else on BT. */
    fun initialTransport(): Transport = when (preference) {
        TransportPreference.Range -> Transport.WifiDirect
        else -> Transport.Bluetooth
    }

    /** Call when a transport is opened, including the first one. */
    fun onTransportStarted(nowMs: Long, transport: Transport) {
        enteredCurrentAt = nowMs
        lossHighSince = NEVER
        disconnectedSince = nowMs
        everConnectedOnCurrent = false
    }

    fun onConnected(nowMs: Long) {
        disconnectedSince = NEVER
        everConnectedOnCurrent = true
        lossHighSince = NEVER
    }

    fun onDisconnected(nowMs: Long) {
        if (disconnectedSince == NEVER) disconnectedSince = nowMs
    }

    /**
     * The one call the selector makes on a timer. Returns the transport to move to, or
     * null to stay put.
     */
    fun evaluate(
        nowMs: Long,
        current: Transport,
        connected: Boolean,
        lossPercent: Double,
    ): SwitchDecision? {
        // The rider pinned a radio; never override that.
        if (preference == TransportPreference.Bluetooth && current == Transport.Bluetooth) return null
        if (switches >= maxSwitches) return null
        if (lastSwitchAt != NEVER && nowMs - lastSwitchAt < minDwellMs) return null

        if (connected) {
            if (lossPercent > lossThresholdPercent) {
                if (lossHighSince == NEVER) lossHighSince = nowMs
                if (nowMs - lossHighSince >= lossWindowMs) {
                    return SwitchDecision(other(current), SwitchReason.Loss)
                }
            } else {
                lossHighSince = NEVER
            }
            return null
        }

        // Not connected from here down.
        if (current == Transport.WifiDirect && !everConnectedOnCurrent &&
            nowMs - enteredCurrentAt >= groupFormMs
        ) {
            return SwitchDecision(Transport.Bluetooth, SwitchReason.GroupTimeout)
        }

        // A Wi-Fi Direct group is allowed its full [groupFormMs] to appear. Without this
        // the generic dead-link rule fires first at [unreachableMs] and the documented
        // 30-second allowance never actually happens.
        val stillFormingGroup = current == Transport.WifiDirect &&
            !everConnectedOnCurrent &&
            nowMs - enteredCurrentAt < groupFormMs
        if (stillFormingGroup) return null

        val down = if (disconnectedSince == NEVER) 0 else nowMs - disconnectedSince
        if (down >= unreachableMs) {
            return SwitchDecision(other(current), SwitchReason.Unreachable)
        }
        return null
    }

    /** Call once the move has actually been made. */
    fun onSwitched(nowMs: Long, to: Transport) {
        switches++
        lastSwitchAt = nowMs
        onTransportStarted(nowMs, to)
    }

    /** With two transports "the next one" is simply the other one. */
    private fun other(t: Transport): Transport = when (t) {
        Transport.Bluetooth -> Transport.WifiDirect
        Transport.WifiDirect -> Transport.Bluetooth
    }

    private companion object { const val NEVER = Long.MIN_VALUE }
}
