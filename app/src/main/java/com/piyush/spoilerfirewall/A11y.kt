package com.piyush.spoilerfirewall

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings

/** Helpers for checking / switching the accessibility service itself. */
object A11y {
    private fun me(ctx: Context) = ComponentName(ctx, SpoilerAccessibilityService::class.java)

    fun isEnabled(ctx: Context): Boolean {
        val list = Settings.Secure.getString(
            ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val me = me(ctx)
        return list.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    /** True if `adb shell pm grant <pkg> android.permission.WRITE_SECURE_SETTINGS` was run. */
    fun canSelfEnable(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /** Adds this service to the enabled list, keeping any others. Returns false if not permitted. */
    fun tryEnable(ctx: Context): Boolean {
        if (isEnabled(ctx)) return true
        if (!canSelfEnable(ctx)) return false
        return try {
            val cr = ctx.contentResolver
            val current = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?.split(':')?.filter { it.isNotBlank() } ?: emptyList()
            val updated = (current + me(ctx).flattenToString()).distinct().joinToString(":")
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, updated)
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            true
        } catch (_: SecurityException) {
            false
        }
    }
}
