package com.piyush.spoilerfirewall

import android.graphics.Bitmap
import android.graphics.Rect

/**
 * 256-bit difference hash of a region: shrink to 17x16 grey, one bit per
 * "left pixel brighter than right". Identical renders of the same post give
 * the same hash; any visibly different picture gives a different one. Exact
 * match only (no "close enough"), so a new image is never mistaken for an old one.
 */
object PixelHash {
    private const val W = 17
    private const val H = 16

    /** @param region in bitmap coordinates. Returns null if it is empty. */
    fun of(src: Bitmap, region: Rect): String? {
        val r = Rect(region)
        if (!r.intersect(0, 0, src.width, src.height) || r.width() < 2 || r.height() < 2) return null
        val crop = Bitmap.createBitmap(src, r.left, r.top, r.width(), r.height())
        val small = Bitmap.createScaledBitmap(crop, W, H, true)
        if (crop !== src) crop.recycle()
        val px = IntArray(W * H)
        small.getPixels(px, 0, W, 0, 0, W, H)
        small.recycle()

        val sb = StringBuilder(80).append(r.width()).append('x').append(r.height()).append(':')
        var acc = 0
        var bits = 0
        for (y in 0 until H) for (x in 0 until W - 1) {
            acc = (acc shl 1) or (if (luma(px[y * W + x]) > luma(px[y * W + x + 1])) 1 else 0)
            if (++bits == 4) {
                sb.append(Character.forDigit(acc, 16))
                acc = 0
                bits = 0
            }
        }
        return sb.toString()
    }

    private fun luma(c: Int): Int =
        ((c shr 16 and 0xFF) * 299 + (c shr 8 and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
}
