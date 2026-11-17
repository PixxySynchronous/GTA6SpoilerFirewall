package com.piyush.spoilerfirewall

import android.graphics.Bitmap
import kotlin.math.max

/**
 * Makes an extremely blurred thumbnail of a screenshot: halve it repeatedly
 * (proper averaging, no sparkle) down to ~24 px wide, then box-blur that tiny
 * image. Stretched back to full screen with bilinear filtering, each original
 * pixel becomes a ~45 px soft blob: colours and rough shapes, no text or faces.
 */
object HeavyBlur {
    private const val TARGET_WIDTH = 24

    fun of(src: Bitmap): Bitmap {
        var cur = src
        while (cur.width / 2 >= TARGET_WIDTH) {
            val next = Bitmap.createScaledBitmap(cur, cur.width / 2, max(1, cur.height / 2), true)
            if (cur !== src) cur.recycle()
            cur = next
        }
        val tiny = if (cur === src) cur.copy(Bitmap.Config.ARGB_8888, true)
        else cur.copy(Bitmap.Config.ARGB_8888, true).also { cur.recycle() }
        repeat(2) { boxBlur(tiny) }
        return tiny
    }

    /** 3x3 average with clamped edges. */
    private fun boxBlur(b: Bitmap) {
        val w = b.width
        val h = b.height
        val src = IntArray(w * h)
        b.getPixels(src, 0, w, 0, 0, w, h)
        val dst = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var r = 0; var g = 0; var bl = 0; var n = 0
            for (dy in -1..1) for (dx in -1..1) {
                val xx = (x + dx).coerceIn(0, w - 1)
                val yy = (y + dy).coerceIn(0, h - 1)
                val c = src[yy * w + xx]
                r += c shr 16 and 0xFF; g += c shr 8 and 0xFF; bl += c and 0xFF; n++
            }
            dst[y * w + x] = (0xFF shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (bl / n)
        }
        b.setPixels(dst, 0, w, 0, 0, w, h)
    }
}
