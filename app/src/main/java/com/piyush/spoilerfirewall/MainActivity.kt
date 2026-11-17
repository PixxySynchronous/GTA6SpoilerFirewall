package com.piyush.spoilerfirewall

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.DateFormat
import java.util.Date

class MainActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var status: TextView
    private lateinit var toggle: Button
    private lateinit var pause: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        val title = TextView(this).apply {
            text = "GTA VI Spoiler Firewall"
            textSize = 24f
            setPadding(0, 48, 0, 16)
        }
        val info = TextView(this).apply {
            text = "While YouTube or Instagram is open, the screen stays covered. Each post, " +
                "Short, Reel or search result is uncovered only after its title/caption " +
                "(and, on Android 14+, the text visible in its picture) has been checked " +
                "on-device. Anything mentioning GTA 6, or that can't be read, stays covered. " +
                "You can still scroll and tap through the cover.\n\n" +
                "Not yet covered: images or videos with no readable text, and audio."
            textSize = 15f
            setPadding(0, 0, 0, 24)
        }
        status = TextView(this).apply { textSize = 16f; setPadding(0, 0, 0, 24) }

        val settingsButton = Button(this).apply {
            text = "Open Accessibility Settings"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        toggle = Button(this).apply {
            setOnClickListener { prefs.enabled = !prefs.enabled; refresh() }
        }
        pause = Button(this).apply {
            setOnClickListener {
                prefs.pausedUntil = if (isPaused()) 0L else System.currentTimeMillis() + 15 * 60_000L
                refresh()
            }
        }
        val pauseVideo = CheckBox(this).apply {
            text = "Pause Shorts/Reels audio while they're being checked"
            isChecked = prefs.pauseVideo
            setOnCheckedChangeListener { _, checked -> prefs.pauseVideo = checked }
        }

        usageButton = Button(this).apply {
            text = "Allow usage access (for the \"firewall is off\" pop-up)"
            setOnClickListener { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
            listOf(title, info, status, settingsButton, toggle, pause, pauseVideo, usageButton).forEach(::addView)
        }
        setContentView(ScrollView(this).apply { addView(layout) })

        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        WatcherService.start(this)
    }

    private lateinit var usageButton: Button

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun isPaused() = prefs.pausedUntil > System.currentTimeMillis()

    private fun refresh() {
        val serviceOn = A11y.isEnabled(this)
        usageButton.visibility = if (WatcherService.hasUsageAccess(this)) View.GONE else View.VISIBLE
        status.text = when {
            !serviceOn -> "⚠ Service is OFF. Enable it in Accessibility settings → Installed apps."
            !prefs.enabled -> "Protection disabled."
            isPaused() -> "Paused until " +
                DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(prefs.pausedUntil))
            else -> "✅ Protection active."
        }
        toggle.text = if (prefs.enabled) "Turn protection off" else "Turn protection on"
        pause.text = if (isPaused()) "Resume protection now" else "Pause for 15 minutes"
    }
}
