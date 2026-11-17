package com.piyush.spoilerfirewall

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager

/**
 * While a full-screen Short/Reel/video is still hidden, hold transient audio
 * focus. YouTube and Instagram pause (or duck) on transient focus loss and
 * resume when it's released, so you don't hear a spoiler behind the cover.
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

    fun hold(want: Boolean) {
        if (want == holding) return
        holding = want
        if (want) audio.requestAudioFocus(request) else audio.abandonAudioFocusRequest(request)
    }
}
