package com.piyush.spoilerfirewall

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager

/**
 * While a full-screen Short/Reel/video is still hidden, keep it silent:
 *  1. hold transient audio focus (YouTube pauses on this), and
 *  2. mute the media stream, because Instagram ignores audio focus.
 * The mute is only undone if we applied it, so a user's own mute is kept.
 * Calls use a different stream and are never affected.
 */
class AudioGuard(context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                .build()
        )
        .setOnAudioFocusChangeListener { }
        .build()
    private var holding = false
    private var mutedByUs = false

    fun hold(want: Boolean) {
        if (want == holding) return
        holding = want
        if (want) {
            audio.requestAudioFocus(request)
            if (!audio.isStreamMute(AudioManager.STREAM_MUSIC)) {
                adjust(AudioManager.ADJUST_MUTE)
                mutedByUs = true
            }
        } else {
            audio.abandonAudioFocusRequest(request)
            if (mutedByUs) {
                adjust(AudioManager.ADJUST_UNMUTE)
                mutedByUs = false
            }
        }
    }

    private fun adjust(direction: Int) {
        try {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0)
        } catch (_: SecurityException) {
            // Do Not Disturb can block volume changes; focus still applies.
        }
    }
}
