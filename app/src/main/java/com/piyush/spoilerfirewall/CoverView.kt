package com.piyush.spoilerfirewall

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.TypedValue
import android.view.View

class BlockedRect(val rect: Rect, val spoiler: Boolean)

/**
 * Full-screen, touch-through overlay. It paints every protected area opaque
 * EXCEPT the holes (cleared items + system windows such as the keyboard).
 * All rects are in screen coordinates.
 */
class CoverView(context: Context) : View(context) {
    private val loc = IntArray(2)
    private val fill = Paint().apply { color = 0xFF121317.toInt() }
    private val checkingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF8E94A3.toInt()
        textAlign = Paint.Align.CENTER
        textSize = sp(15f)
        setShadowLayer(6f, 0f, 1f, 0xCC000000.toInt()) // readable over the blur
    }
    private val spoilerPaint = Paint(checkingPaint).apply { color = 0xFFFF8A80.toInt() }

    // Heavy blur: a tiny (~24 px wide) smoothed copy of the app window, stretched
    // back over the covered areas with bilinear filtering. Unreadable by design.
    private var blur: Bitmap? = null
    private val blurDst = Rect()
    private val blurPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    /** Darkens the blur slightly so labels stay readable. */
    private val tint = Paint().apply { color = 0x66000000 }

    /** @param window screen rect the blurred frame was taken from; null clears it. */
    fun setBlur(bitmap: Bitmap?, window: Rect?) {
        if (blur !== bitmap) blur?.recycle()
        blur = bitmap
        if (window != null) blurDst.set(window)
        invalidate()
    }

    private var areas: List<Rect> = emptyList()
    private var holes: List<Rect> = emptyList()
    private var exclusions: List<Rect> = emptyList()
    private var labels: List<BlockedRect> = emptyList()

    /**
     * Paint order: [areas] minus [holes] minus [exclusions]; then every blocked
     * rect in [labels] again (minus [exclusions]) so blocked always wins over holes.
     */
    fun update(areas: List<Rect>, holes: List<Rect>, exclusions: List<Rect>, labels: List<BlockedRect>) {
        this.areas = areas
        this.holes = holes
        this.exclusions = exclusions
        this.labels = labels
        invalidate()
    }

    /** Cover everything immediately (used on scroll / app entry). */
    fun coverAll(area: Rect) = update(listOf(area), emptyList(), emptyList(), emptyList())

    /** Close any hole that touches [region] right now, without waiting for a rescan. */
    fun closeHolesIn(region: Rect) {
        val kept = holes.filterNot { Rect.intersects(it, region) }
        if (kept.size != holes.size) {
            holes = kept
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        getLocationOnScreen(loc)
        canvas.save()
        canvas.translate(-loc[0].toFloat(), -loc[1].toFloat())
        for (area in areas) {
            canvas.save()
            canvas.clipRect(area)
            for (e in exclusions) canvas.clipOutRect(e)
            canvas.save()
            for (h in holes) canvas.clipOutRect(h)
            paintCover(canvas, area)
            canvas.restore()
            for (b in labels) {
                canvas.save()
                canvas.clipRect(b.rect)
                paintCover(canvas, b.rect)
                canvas.restore()
            }
            val drawn = ArrayList<Rect>()
            // Spoiler labels first so they win over overlapping "Checking…" ones.
            for (b in labels.sortedByDescending { it.spoiler }) {
                val r = b.rect
                if (r.width() < 300 || r.height() < 140) continue
                if (!area.contains(r.centerX(), r.centerY())) continue
                val slot = Rect(r.centerX() - 300, r.centerY() - 70, r.centerX() + 300, r.centerY() + 70)
                if (drawn.any { Rect.intersects(it, slot) }) continue
                drawn += slot
                canvas.drawText(
                    if (b.spoiler) "Spoiler blocked" else "Checking…",
                    r.exactCenterX(), r.exactCenterY(),
                    if (b.spoiler) spoilerPaint else checkingPaint
                )
            }
            canvas.restore()
        }
        canvas.restore()
    }

    /** Opaque base, then the blurred frame (if any) and a light tint, within the current clip. */
    private fun paintCover(canvas: Canvas, region: Rect) {
        canvas.drawRect(region, fill)
        val b = blur ?: return
        if (b.isRecycled) return
        canvas.drawBitmap(b, null, blurDst, blurPaint)
        canvas.drawRect(region, tint)
    }

    private fun sp(v: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)
}
