package com.piyush.spoilerfirewall

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restarts the watcher after a reboot or an app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> WatcherService.start(ctx)
        }
    }
}
