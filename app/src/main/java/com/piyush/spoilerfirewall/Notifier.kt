package com.piyush.spoilerfirewall

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.provider.Settings

/** Status pop-ups shown while YouTube / Instagram is open. */
object Notifier {
    private const val CH_STATUS = "status"
    private const val CH_WATCHER = "watcher"
    /**
     * One notification for everything. Android 16 folds an app's separate
     * notifications into a collapsed bundle (hiding the buttons), so the
     * watcher's foreground notification doubles as the on/off pop-up.
     */
    const val ID_WATCHER = 1
    private const val ID_STATUS = ID_WATCHER

    enum class Status { ON, OFF_PAUSED, OFF_SERVICE }

    fun appName(pkg: String?) = when (pkg) {
        SpoilerAccessibilityService.INSTAGRAM -> "Instagram"
        SpoilerAccessibilityService.YOUTUBE -> "YouTube"
        else -> "This app"
    }

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_STATUS, "Firewall on/off pop-up", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Shown while YouTube or Instagram is open, with a button to switch the firewall on or off."
                setSound(null, null)
                enableVibration(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_WATCHER, "Background watcher", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Required by Android so the app can notice YouTube/Instagram opening while the firewall is off."
                setShowBadge(false)
            }
        )
    }

    /**
     * @param alert true when the user just opened the app: (re)pops the heads-up.
     *              false for in-place updates (e.g. after tapping Turn off).
     */
    fun showStatus(ctx: Context, pkg: String?, status: Status, alert: Boolean) {
        ensureChannels(ctx)
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val app = appName(pkg)
        val b = Notification.Builder(ctx, CH_STATUS)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .setOnlyAlertOnce(!alert)
            .setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(activityIntent(ctx, Intent(ctx, MainActivity::class.java), 10))

        when (status) {
            Status.ON -> b.setContentTitle("Spoiler Firewall is ON · $app")
                .setContentText("Posts stay covered until they're checked.")
                .addAction(action(ctx, "Turn off", ActionReceiver.ACTION_DISABLE, 11))
                .addAction(action(ctx, "Pause 15 min", ActionReceiver.ACTION_PAUSE, 12))
            Status.OFF_PAUSED -> b.setContentTitle("Spoiler Firewall is OFF")
                .setContentText("$app is not being filtered.")
                .addAction(action(ctx, "Turn on", ActionReceiver.ACTION_ENABLE, 13))
            Status.OFF_SERVICE -> {
                b.setContentTitle("Spoiler Firewall is OFF")
                    .setContentText("Accessibility service is disabled, so $app is not being filtered.")
                val turnOn = if (A11y.canSelfEnable(ctx)) {
                    action(ctx, "Turn on", ActionReceiver.ACTION_ENABLE, 14)
                } else {
                    val i = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    Notification.Action.Builder(null, "Turn on", activityIntent(ctx, i, 15)).build()
                }
                b.addAction(turnOn)
            }
        }
        try {
            nm.notify(ID_STATUS, b.build())
        } catch (_: SecurityException) {
            // Notification permission not granted.
        }
    }

    /** Back to the quiet watcher note (or nothing, if the watcher isn't running). */
    fun cancelStatus(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (WatcherService.running) {
            try { nm.notify(ID_WATCHER, watcherNotification(ctx)) } catch (_: SecurityException) {}
        } else {
            nm.cancel(ID_STATUS)
        }
    }

    fun watcherNotification(ctx: Context): Notification {
        ensureChannels(ctx)
        return Notification.Builder(ctx, CH_WATCHER)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("Spoiler Firewall watcher")
            .setContentText("Tells you if YouTube/Instagram opens while the firewall is off.")
            .setOngoing(true)
            .setContentIntent(activityIntent(ctx, Intent(ctx, MainActivity::class.java), 20))
            .build()
    }

    private fun action(ctx: Context, label: String, action: String, req: Int): Notification.Action {
        val pi = PendingIntent.getBroadcast(
            ctx, req,
            Intent(ctx, ActionReceiver::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Action.Builder(null, label, pi).build()
    }

    private fun activityIntent(ctx: Context, i: Intent, req: Int) = PendingIntent.getActivity(
        ctx, req, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )
}
