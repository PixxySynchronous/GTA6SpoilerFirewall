package com.piyush.spoilerfirewall

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Handles the Turn off / Pause / Turn on buttons in the status pop-up. */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val prefs = Prefs(ctx)
        when (intent.action) {
            ACTION_DISABLE -> prefs.enabled = false
            ACTION_PAUSE -> prefs.pausedUntil = System.currentTimeMillis() + 15 * 60_000L
            ACTION_ENABLE -> {
                prefs.enabled = true
                prefs.pausedUntil = 0L
                A11y.tryEnable(ctx)
            }
        }
        // The running accessibility service listens to these prefs and updates
        // the cover + pop-up itself.
    }

    companion object {
        const val ACTION_DISABLE = "com.piyush.spoilerfirewall.DISABLE"
        const val ACTION_PAUSE = "com.piyush.spoilerfirewall.PAUSE"
        const val ACTION_ENABLE = "com.piyush.spoilerfirewall.ENABLE"
    }
}
