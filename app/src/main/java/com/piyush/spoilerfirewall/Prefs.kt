package com.piyush.spoilerfirewall

import android.content.Context
import android.content.SharedPreferences

class Prefs(context: Context) {
    val sp: SharedPreferences = context.getSharedPreferences("firewall", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = sp.getBoolean(KEY_ENABLED, true)
        set(v) = sp.edit().putBoolean(KEY_ENABLED, v).apply()

    var pausedUntil: Long
        get() = sp.getLong(KEY_PAUSED_UNTIL, 0L)
        set(v) = sp.edit().putLong(KEY_PAUSED_UNTIL, v).apply()

    var pauseVideo: Boolean
        get() = sp.getBoolean(KEY_PAUSE_VIDEO, true)
        set(v) = sp.edit().putBoolean(KEY_PAUSE_VIDEO, v).apply()

    fun isProtecting(now: Long = System.currentTimeMillis()) = enabled && now >= pausedUntil

    companion object {
        const val KEY_ENABLED = "enabled"
        const val KEY_PAUSED_UNTIL = "paused_until"
        const val KEY_PAUSE_VIDEO = "pause_video"
    }
}
