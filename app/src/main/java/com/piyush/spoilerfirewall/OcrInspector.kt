package com.piyush.spoilerfirewall

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class OcrLine(val bounds: Rect, val text: String)

/** A screenshot of ONLY the target app's window, plus how to map screen rects onto it. */
class WindowShot(val bitmap: Bitmap, private val windowBounds: Rect) {
    private val sx = bitmap.width.toFloat() / windowBounds.width()
    private val sy = bitmap.height.toFloat() / windowBounds.height()

    fun toBitmap(screen: Rect) = Rect(
        ((screen.left - windowBounds.left) * sx).toInt(),
        ((screen.top - windowBounds.top) * sy).toInt(),
        ((screen.right - windowBounds.left) * sx).toInt(),
        ((screen.bottom - windowBounds.top) * sy).toInt()
    )

    fun toScreen(b: Rect) = Rect(
        windowBounds.left + (b.left / sx).toInt(),
        windowBounds.top + (b.top / sy).toInt(),
        windowBounds.left + (b.right / sx).toInt(),
        windowBounds.top + (b.bottom / sy).toInt()
    )
}

/**
 * Screenshots ONLY the target app's window (Android 14+), so our own cover is
 * never in the picture. Capture (cheap) and OCR (expensive) are separate so the
 * caller can skip OCR when every item is already known by its fingerprint.
 */
class OcrInspector(private val service: AccessibilityService) {
    val available: Boolean = Build.VERSION.SDK_INT >= 34
    var busy = false
        private set
    var nextAllowedAt = 0L
        private set

    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    /** Marks the inspector busy; the caller must call [release] when done with the shot. */
    fun capture(windowId: Int, windowBounds: Rect, done: (WindowShot?) -> Unit) {
        if (Build.VERSION.SDK_INT < 34) {
            done(null)
            return
        }
        busy = true
        // The platform rejects screenshots taken faster than ~3 per second.
        nextAllowedAt = SystemClock.uptimeMillis() + MIN_INTERVAL_MS

        service.takeScreenshotOfWindow(windowId, service.mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val bitmap: Bitmap? = try {
                    val hw = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                    val copy = hw?.copy(Bitmap.Config.ARGB_8888, false)
                    hw?.recycle()
                    copy
                } catch (_: Exception) {
                    null
                } finally {
                    result.hardwareBuffer.close()
                }
                if (bitmap == null) {
                    busy = false
                    done(null)
                } else {
                    done(WindowShot(bitmap, windowBounds))
                }
            }

            override fun onFailure(errorCode: Int) {
                busy = false
                done(null)
            }
        })
    }

    /** On-device OCR of the shot; line bounds are returned in screen coordinates. */
    fun recognize(shot: WindowShot, done: (List<OcrLine>?) -> Unit) {
        recognizer.process(InputImage.fromBitmap(shot.bitmap, 0))
            .addOnSuccessListener { text ->
                done(text.textBlocks.flatMap { it.lines }.mapNotNull { line ->
                    val b = line.boundingBox ?: return@mapNotNull null
                    OcrLine(shot.toScreen(b), line.text)
                })
            }
            .addOnFailureListener { done(null) }
    }

    fun release(shot: WindowShot?) {
        shot?.bitmap?.recycle()
        busy = false
    }

    fun close() {
        if (available) recognizer.close()
    }

    private companion object {
        const val MIN_INTERVAL_MS = 350L
    }
}
