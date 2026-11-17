package com.piyush.spoilerfirewall

import android.app.AppOpsManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.Settings

/**
 * Runs only to cover the case where the accessibility service itself is
 * disabled (it can't see anything then). While it's disabled, this polls
 * usage events and pops "Firewall is OFF - Turn on" when YouTube/Instagram
 * comes to the foreground. While the service is enabled it does nothing.
 */
class WatcherService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var polling = false
    private var lastQuery = 0L
    private var foreground: String? = null
    private var shownFor: String? = null

    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) = refreshMode()
    }
    private val poll = object : Runnable {
        override fun run() {
            checkForeground()
            if (polling) handler.postDelayed(this, POLL_MS)
        }
    }

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        running = true
        val n = Notifier.watcherNotification(this)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(Notifier.ID_WATCHER, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(Notifier.ID_WATCHER, n)
        }
        contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), false, observer
        )
        refreshMode()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        running = false
        contentResolver.unregisterContentObserver(observer)
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun refreshMode() {
        val shouldPoll = !A11y.isEnabled(this) && hasUsageAccess(this)
        if (shouldPoll == polling) return
        polling = shouldPoll
        if (polling) {
            // Look back far enough to know what is in the foreground right now.
            lastQuery = System.currentTimeMillis() - 30 * 60_000L
            foreground = null
            handler.post(poll)
        } else {
            handler.removeCallbacks(poll)
            // Service is back: it owns the pop-up now.
            if (shownFor != null) shownFor = null
        }
    }

    private fun checkForeground() {
        val usm = getSystemService(UsageStatsManager::class.java)
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(lastQuery, now)
        lastQuery = now
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) foreground = e.packageName
        }
        val fg = foreground
        val inProtected = fg != null && fg in SpoilerAccessibilityService.PROTECTED_PACKAGES
        if (inProtected && shownFor != fg) {
            Notifier.showStatus(this, fg, Notifier.Status.OFF_SERVICE, alert = true)
            shownFor = fg
        } else if (!inProtected && shownFor != null) {
            Notifier.cancelStatus(this)
            shownFor = null
        }
    }

    companion object {
        private const val POLL_MS = 1000L

        @Volatile
        var running = false
            private set

        fun start(ctx: Context) {
            try {
                ctx.startForegroundService(Intent(ctx, WatcherService::class.java))
            } catch (_: Exception) {
                // Background-start restrictions; MainActivity / boot will start it.
            }
        }

        fun hasUsageAccess(ctx: Context): Boolean {
            val ops = ctx.getSystemService(AppOpsManager::class.java)
            val mode = ops.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName
            )
            return mode == AppOpsManager.MODE_ALLOWED
        }
    }
}
