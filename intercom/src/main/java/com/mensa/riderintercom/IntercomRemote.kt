package com.mensa.riderintercom

import android.content.Context
import com.mensa.riderintercom.call.IntercomService

/**
 * The intercom's control surface for a host app. In Navigator that host is the
 * KTM handlebar remote: the rider talks to the pillion by holding a bike button.
 *
 * The contract, and the reason this object exists rather than the host reaching
 * into [IntercomService] directly:
 *
 * - **It only ever controls a call that is already running.** Every function is
 *   a no-op returning false/null when there is no call. Nothing here can START
 *   one, deliberately: Android 14 refuses to create a microphone-type foreground
 *   service from the background, and a bike button is pressed with the phone in
 *   a mount, screen off, Maps in front. Starting stays a tap on the phone.
 * - **It never starts the service by accident.** It talks to the live instance
 *   in-process. An intent to a service that isn't running would start it
 *   without the foreground promise it needs.
 *
 * Call from the main thread: the controller updates UI state flows.
 */
object IntercomRemote {

    /** True while a call is up (hosting, joining, or connected). */
    val isCallActive: Boolean
        get() = IntercomService.instance.value != null

    /**
     * Hold-to-talk: true on press, false on release. In open-call mode this is
     * "cut in" over the voice detector; in push-to-talk mode it is the only way
     * the channel opens. Returns false when there is no call to talk on.
     */
    fun setPushToTalk(down: Boolean): Boolean {
        val service = IntercomService.instance.value ?: return false
        service.controller.setPushToTalk(down)
        return true
    }

    /**
     * Step noise suppression Off -> Light -> Strong -> Off on the live capture
     * path, so it can be judged against real wind at speed. Returns the new
     * level's label for the host to show, or null when there is no call.
     */
    fun cycleNoiseSuppression(): String? {
        val service = IntercomService.instance.value ?: return null
        return service.controller.cycleNoiseSuppression().label
    }

    /** End the call. Stopping a service is always allowed from the background. */
    fun endCall(context: Context): Boolean {
        if (IntercomService.instance.value == null) return false
        IntercomService.stopCall(context)
        return true
    }
}
