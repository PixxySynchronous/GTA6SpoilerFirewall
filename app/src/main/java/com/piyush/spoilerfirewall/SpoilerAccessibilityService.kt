package com.piyush.spoilerfirewall

import android.accessibilityservice.AccessibilityService
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Block-by-default spoiler firewall.
 *
 * While Instagram or YouTube is on screen, an opaque cover sits over the whole
 * app window. A region is only uncovered once the item shown there has been
 * cleared:
 *   1. its accessibility text (titles, captions, channel names) passes
 *      [SpoilerRules], and
 *   2. on Android 14+, OCR of the app window found no spoiler text in it
 *      (thumbnail text, on-video text).
 * Any scroll closes the holes in the scrolled area immediately; they reopen
 * only after the list settles and the new items are checked.
 *
 * Android has no pre-render hook for other apps, so the remaining exposure is
 * the few frames between the app moving its content and our scroll event.
 */
class SpoilerAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    private lateinit var ocr: OcrInspector
    private lateinit var audioGuard: AudioGuard
    private val scanner = FeedScanner()
    private var cover: CoverView? = null

    // --- state -------------------------------------------------------------
    private var protectedVisible = false
    private var scrollGeneration = 0
    private val activeScrolls = ArrayList<Pair<Rect, Long>>()
    /** Rects revealed since the last scroll there; content at these spots was already checked. */
    private val revealedSinceScroll = HashMap<String, String>() // rect -> text signature

    private val digits = Regex("\\d+")

    /** Text identity ignoring numbers (like/view counts, timers); null if there's no text. */
    private fun signature(item: ScreenItem): String? =
        item.judgeText.takeIf { it.isNotBlank() }?.replace(digits, "#")?.let { "${item.kind}|${it.hashCode()}|${it.length}" }
    private val ocrCleared = LruSet(1500)
    private val ocrSpoiler = LruSet(1500)
    private var looseOcrSpoilerGeneration = -1
    private val knownProtectedWindowIds = HashSet<Int>()
    private var lastSnapshot: Snapshot? = null

    private class Snapshot(
        val windowId: Int,
        val windowBounds: Rect,
        val items: List<ScreenItem>,
        val generation: Int,
        val containers: Set<ScreenItem>
    )

    // --- scheduling --------------------------------------------------------
    private var recomputeAt = Long.MAX_VALUE
    private val recomputeRunnable = Runnable {
        recomputeAt = Long.MAX_VALUE
        recompute()
    }
    private val ocrRunnable = Runnable { requestOcr() }
    private val periodicOcr = object : Runnable {
        override fun run() {
            // Re-sample what's visible (e.g. text appearing inside a playing video).
            if (protectedVisible && activeScrolls.isEmpty()) requestOcr(force = true)
            handler.postDelayed(this, PERIODIC_OCR_MS)
        }
    }
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        val until = prefs.pausedUntil - System.currentTimeMillis()
        if (until > 0) handler.postDelayed({ schedule(0) }, until + 50)
        schedule(0)
    }

    /** Never starves: an earlier deadline wins, later requests don't push it back. */
    private fun schedule(delayMs: Long) {
        val at = SystemClock.uptimeMillis() + delayMs
        if (at >= recomputeAt) return
        handler.removeCallbacks(recomputeRunnable)
        recomputeAt = at
        handler.postAtTime(recomputeRunnable, at)
    }

    // --- lifecycle ---------------------------------------------------------
    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = Prefs(this)
        prefs.sp.registerOnSharedPreferenceChangeListener(prefListener)
        ocr = OcrInspector(this)
        audioGuard = AudioGuard(this)
        addCoverWindow()
        handler.postDelayed(periodicOcr, PERIODIC_OCR_MS)
        WatcherService.start(this)
        schedule(0) // if YouTube/Instagram is already open, show the pop-up now
    }

    private fun addCoverWindow() {
        val view = CoverView(this).apply { visibility = View.GONE }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // NOT_TOUCHABLE: you can still scroll/tap the app through the cover.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            fitInsetsTypes = 0
        }
        try {
            getSystemService(WindowManager::class.java).addView(view, params)
            cover = view
        } catch (_: Exception) {
            cover = null
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (::prefs.isInitialized) prefs.sp.unregisterOnSharedPreferenceChangeListener(prefListener)
        if (::audioGuard.isInitialized) audioGuard.hold(false)
        cover?.let {
            try { getSystemService(WindowManager::class.java).removeView(it) } catch (_: Exception) {}
        }
        cover = null
        Notifier.cancelStatus(this) // the watcher takes over and will show "OFF"
        if (::ocr.isInitialized) ocr.close()
        super.onDestroy()
    }

    // --- events ------------------------------------------------------------
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !::prefs.isInitialized) return
        if (!prefs.isProtecting()) {
            hideCover()
            // Still track app switches so the "Firewall is OFF" pop-up can appear.
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
            ) schedule(CONTENT_DELAY_MS)
            return
        }
        val pkg = event.packageName?.toString()
        if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) checkKeyboard()
        if (pkg != null && pkg in PROTECTED_PACKAGES) {
            if (!protectedVisible) showFullCover()
            when (event.eventType) {
                AccessibilityEvent.TYPE_VIEW_FOCUSED, AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                    // Tapping a text box: the keyboard is about to open, and the app
                    // reports the resulting layout shift BEFORE the keyboard window
                    // exists, so expect it now.
                    val editable = event.className?.contains("EditText") == true ||
                        event.source?.isEditable == true
                    if (editable) keyboardChangedAt = SystemClock.uptimeMillis()
                }
                AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                    // The keyboard opening/closing resizes the app and shifts its
                    // content, which arrives as scroll events. That's not new
                    // content: keep cleared items and just re-place them.
                    checkKeyboard()
                    if (inKeyboardShift()) {
                        schedule(CONTENT_DELAY_MS)
                        return
                    }
                    val src = Rect()
                    event.source?.getBoundsInScreen(src)
                    // On a full-screen page (Reel, Short, story) any swipe signal
                    // re-covers everything at once: the app may report only an inner
                    // view moving while the whole page slides.
                    onScroll(if (src.isEmpty || fullScreenPageShown) null else src)
                    return
                }
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                    onScroll(null) // new screen / dialog: trust nothing on it yet
                    return
                }
            }
        }
        schedule(CONTENT_DELAY_MS)
    }

    // --- keyboard ------------------------------------------------------------
    private var keyboardShown = false
    private var keyboardChangedAt = 0L

    private fun checkKeyboard() {
        val shown = try {
            windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        } catch (_: Exception) {
            keyboardShown
        }
        if (shown != keyboardShown) {
            keyboardShown = shown
            val now = SystemClock.uptimeMillis()
            keyboardChangedAt = now
            // A "scroll" just before the keyboard appeared/disappeared was really
            // the app resizing for it: stop waiting on it and re-place items.
            if (now - lastScrollAt < KEYBOARD_LOOKBACK_MS) {
                activeScrolls.clear()
                schedule(0)
            }
        }
    }

    private fun inKeyboardShift() = SystemClock.uptimeMillis() - keyboardChangedAt < KEYBOARD_GRACE_MS

    private var lastScrollAt = 0L

    /** True while a page covering most of the window (Reel/Short/story) is on screen. */
    private var fullScreenPageShown = false

    private fun onScroll(region: Rect?) {
        val r = region ?: EVERYTHING
        if (BuildConfig.DEBUG) android.util.Log.d(TAG, "scroll region=${region?.toShortString()} t=${SystemClock.uptimeMillis() % 100000}")
        scrollGeneration++
        lastScrollAt = SystemClock.uptimeMillis()
        pixelKeys.clear() // fingerprints are per generation; re-taken after the scroll settles
        if (pixelKeysBySize.size > 500) pixelKeysBySize.clear()
        activeScrolls += Rect(r) to SystemClock.uptimeMillis()
        revealedSinceScroll.keys.removeAll { Rect.intersects(Rect.unflattenFromString(it) ?: EVERYTHING, r) }
        cover?.closeHolesIn(r)
        schedule(SETTLE_MS)
    }

    private fun showFullCover() {
        val c = cover ?: return
        c.coverAll(EVERYTHING) // refined to the app window on the next recompute
        c.visibility = View.VISIBLE
        protectedVisible = true
    }

    private fun hideCover() {
        if (!protectedVisible && cover?.visibility != View.VISIBLE) return
        protectedVisible = false
        cover?.visibility = View.GONE
        cover?.setBlur(null, null) // never show one app's blurred frame over another
        blurGeneration = -1
        audioGuard.hold(false)
        fullScreenPageShown = false
        activeScrolls.clear()
        revealedSinceScroll.clear()
    }

    // --- core decision -----------------------------------------------------
    private fun recompute() {
        val c = cover ?: return
        val allWindows = try { windows } catch (_: Exception) { null }
        if (allWindows == null) {
            // Can't see the screen: fail closed if we were protecting.
            if (protectedVisible) schedule(SETTLE_MS)
            return
        }

        val protectedWins = allWindows.filter { isProtectedWindow(it) }
        knownProtectedWindowIds.retainAll(allWindows.map { it.id }.toSet())
        val protecting = prefs.isProtecting()
        updateStatusPopup(allWindows, protectedWins, protecting)
        if (protectedWins.isEmpty() || !protecting) {
            hideCover()
            return
        }
        if (!protectedVisible) showFullCover()

        val now = SystemClock.uptimeMillis()
        activeScrolls.removeAll { now - it.second >= SETTLE_MS }

        // Keyboard and full-width system windows above the app (status/nav bar, shade)
        // stay visible. Narrow system windows (e.g. Samsung's transparent Edge panel
        // handle) are NOT excluded: the app would show through them.
        val minLayer = protectedWins.minOf { it.layer }
        // The status bar is transparent and apps draw under it, so it is covered too
        // (a full-width system strip touching the top edge).
        val screenWidth = protectedWins.maxOf { Rect().also(it::getBoundsInScreen).width() }
        val exclusions = allWindows
            .filter { it !in protectedWins && it.layer > minLayer }
            .mapNotNull { w ->
                val r = Rect().also(w::getBoundsInScreen)
                when (w.type) {
                    AccessibilityWindowInfo.TYPE_INPUT_METHOD -> r
                    AccessibilityWindowInfo.TYPE_SYSTEM -> r.takeIf {
                        val fullWidth = it.width() >= screenWidth * 0.9
                        val statusBar = it.top <= 0 && it.height() < 300
                        fullWidth && !statusBar
                    }
                    else -> null
                }
            }

        val areas = ArrayList<Rect>()
        val items = ArrayList<ScreenItem>()
        var mainWindow: AccessibilityWindowInfo? = null
        var mainArea = 0L
        for (w in protectedWins) {
            val wb = Rect().also(w::getBoundsInScreen)
            areas += wb
            val area = wb.width().toLong() * wb.height()
            if (area > mainArea) { mainArea = area; mainWindow = w }
            val root = w.root ?: continue // no tree yet -> whole window stays covered
            val instagram = root.packageName?.toString() == INSTAGRAM
            items += scanner.scan(
                root, wb,
                neighbourContext = instagram,
                generation = scrollGeneration,
                trustedTextLen = if (instagram) 60 else 25
            )
        }
        // Near-full-window nodes are usually layout layers, not content. YouTube has
        // an invisible full-screen labelled leaf (clearing it would open a hole over
        // everything) and a text-less full-screen page (blocking it would paint over
        // everything). Drop those. Keep full-screen *pages with text*: Instagram
        // stories / Reels are real content units judged on their whole subtree.
        items.removeAll {
            val huge = it.bounds.width().toLong() * it.bounds.height() >= mainArea * 0.9
            huge && (it.kind != ItemKind.FEED || it.judgeText.isBlank())
        }

        val tree = items.map { SpoilerRules.evaluate(it.judgeText, if (it.kind == ItemKind.FEED) 3 else 1) }
        // Overlays that sit (70%+) on a feed post (Instagram's video surface,
        // gradient strips) belong to that post: they share its identity, so a
        // playing video doesn't make a known post look new on every scroll.
        val owners = HashMap<ScreenItem, ScreenItem>()
        val feedItems = items.filter { it.kind == ItemKind.FEED }
        for (item in items) {
            if (item.kind == ItemKind.FEED) continue
            val area = item.bounds.width().toLong() * item.bounds.height()
            if (area <= 0) continue
            feedItems.firstOrNull { f ->
                val o = Rect(item.bounds)
                o.intersect(f.bounds) && o.width().toLong() * o.height() >= area * 0.7
            }?.let { owners[item] = it }
        }
        val keys = items.map { effectiveKey(owners[it] ?: it) }
        // Containers (an item with 2+ other items inside it, e.g. Instagram's feed
        // page or an invisible full-size layer) change pixels on every scroll, so
        // they'd never be found in the cache. Their contents are checked item by
        // item, and blocked items are painted over holes, so they skip OCR.
        val containers = items.filter { outer ->
            items.count {
                it !== outer && owners[it] !== outer && it.bounds != outer.bounds && outer.bounds.contains(it.bounds)
            } >= 2
        }.toSet() +
            // Icons / logos / tiny labels: too small to carry a readable spoiler,
            // and they shift a little whenever a header slides, so skip OCR.
            items.filter { it.bounds.width().toLong() * it.bounds.height() < mainArea * 0.02 }
        val anySpoilerOnScreen = tree.any { it == Verdict.SPOILER } ||
            keys.any { it != null && it in ocrSpoiler } ||
            looseOcrSpoilerGeneration == scrollGeneration
        val screenTextLen = items.sumOf { it.judgeText.length }
        val screenOk = !anySpoilerOnScreen && screenTextLen >= 20

        val holes = ArrayList<Rect>()
        val blocked = ArrayList<BlockedRect>()
        var needOcr = false

        items.forEachIndexed { i, item ->
            val b = item.bounds
            val big = b.width().toLong() * b.height() > mainArea * 0.15
            val base = when (item.kind) {
                ItemKind.FEED -> tree[i]
                ItemKind.LABELLED -> when {
                    tree[i] == Verdict.SPOILER -> Verdict.SPOILER
                    big && !screenOk -> Verdict.UNKNOWN // e.g. "Video player" on a GTA watch page
                    else -> tree[i]
                }
                // Judged by its local context (miniplayer title, card caption) when
                // it has one; otherwise by the whole screen as before.
                ItemKind.UNLABELLED -> when {
                    item.judgeText.isNotBlank() -> tree[i]
                    screenOk -> Verdict.SAFE
                    else -> Verdict.UNKNOWN
                }
            }
            val key = keys[i]
            val spoiler = base == Verdict.SPOILER || (key != null && key in ocrSpoiler)
            val scrolling = activeScrolls.any { Rect.intersects(it.first, b) }
            // Shortcut: the same item (same spot, same text apart from numbers such as
            // like counts) that was already uncovered stays uncovered without waiting
            // for OCR. A new Reel/Short in the same spot has different text, so it
            // does NOT get this shortcut and is covered until fully checked.
            val sig = signature(owners[item] ?: item)
            val sameAsBefore = sig != null && revealedSinceScroll[b.flattenToString()] == sig
            val ocrOk = !ocr.available || item in containers || (key != null && key in ocrCleared) || sameAsBefore
            if (!spoiler && base == Verdict.SAFE && ocrOk && !scrolling) {
                holes += b
                if (sig != null) revealedSinceScroll[b.flattenToString()] = sig
            } else {
                // Blocked because something on this screen is a spoiler (e.g. the
                // video of a Short whose caption is GTA): say so instead of "Checking…".
                val becauseOfSpoiler = spoiler || (base == Verdict.UNKNOWN && anySpoilerOnScreen && big)
                blocked += BlockedRect(b, becauseOfSpoiler)
                if (!spoiler && base == Verdict.SAFE && !ocrOk && !scrolling) needOcr = true
            }
        }

        // CoverView paints blocked rects on top of holes, so a hole can never
        // expose something blocked (e.g. buttons over a hidden player).
        val finalHoles = holes

        c.update(areas, finalHoles, exclusions, blocked)
        if (BuildConfig.DEBUG) {
            android.util.Log.d(
                TAG,
                "items=${items.size} holes=${finalHoles.size} blocked=${blocked.size} " +
                    "spoilers=${blocked.count { it.spoiler }} needOcr=$needOcr scrolling=${activeScrolls.size} " +
                    "feed=${items.count { it.kind == ItemKind.FEED }} " +
                    "holeRects=${finalHoles.map { it.toShortString() }} blockedRects=${blocked.map { it.rect.toShortString() }} " +
                    "cached=${keys.count { it != null && it in ocrCleared }} " +
                    "spoilerTexts=${items.filterIndexed { i, _ -> tree[i] == Verdict.SPOILER || keys[i]?.let { k -> k in ocrSpoiler } == true }.map { it.judgeText.take(50).replace('\n', ' ') }.take(4)}"
            )
        }

        fullScreenPageShown = items.any { it.bounds.width().toLong() * it.bounds.height() >= mainArea * 0.45 }

        // Silence a full-screen video (Short / Reel / player) while it's hidden.
        val bigBlocked = blocked.any { it.rect.width().toLong() * it.rect.height() >= mainArea * 0.45 }
        audioGuard.hold(prefs.pauseVideo && bigBlocked)

        mainWindow?.let { w ->
            lastSnapshot = Snapshot(w.id, Rect().also(w::getBoundsInScreen), items, scrollGeneration, containers + owners.keys)
        }
        // Also screenshot when something is covered but the blur is from before
        // the last scroll (OCR is skipped if every item is already known).
        val blurStale = blocked.isNotEmpty() && blurGeneration != scrollGeneration
        if ((needOcr || blurStale) && activeScrolls.isEmpty()) requestOcr()
        if (activeScrolls.isNotEmpty()) schedule(SETTLE_MS)
    }

    // --- status pop-up -----------------------------------------------------
    private var popupApp: String? = null
    private var popupStatus: Notifier.Status? = null

    private fun updateStatusPopup(
        allWindows: List<AccessibilityWindowInfo>,
        protectedWins: List<AccessibilityWindowInfo>,
        protecting: Boolean
    ) {
        if (protectedWins.isEmpty()) {
            // Only treat it as "left the app" when another app / the home screen is
            // in front. The notification shade hides YouTube's window too, and the
            // pop-up must stay available there.
            val otherAppInFront = allWindows.any { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            if (popupApp != null && otherAppInFront) {
                Notifier.cancelStatus(this)
                popupApp = null
                popupStatus = null
            }
            return
        }
        val app = protectedWins.firstNotNullOfOrNull { it.root?.packageName?.toString() } ?: popupApp
        val status = if (protecting) Notifier.Status.ON else Notifier.Status.OFF_PAUSED
        if (app == popupApp && status == popupStatus) return
        // Pop up when entering the app (or switching between YouTube and Instagram);
        // just update in place when only the on/off state changed.
        Notifier.showStatus(this, app, status, alert = app != popupApp)
        popupApp = app
        popupStatus = status
    }

    private fun isProtectedWindow(w: AccessibilityWindowInfo): Boolean {
        if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) return false
        // Picture-in-picture is a video the user chose and was already watching
        // (it has no text to check anyway), so it is never covered.
        if (w.isInPictureInPictureMode) return false
        val pkg = w.root?.packageName?.toString()
        if (pkg != null) {
            return if (pkg in PROTECTED_PACKAGES) {
                knownProtectedWindowIds += w.id
                true
            } else {
                knownProtectedWindowIds -= w.id
                false
            }
        }
        // Tree momentarily unavailable: keep treating a known window as protected.
        return w.id in knownProtectedWindowIds
    }

    // --- identity cache ------------------------------------------------------
    /**
     * Pixel fingerprints for the current scroll generation: item slot -> "key#hash".
     * Rebuilt from a fresh screenshot after every scroll, so an item is only
     * recognised if its text AND pixels match something checked before.
     */
    private val pixelKeys = HashMap<String, String>()

    /** Scroll generation the current blurred backdrop was taken in (-1 = none). */
    private var blurGeneration = -1

    /**
     * Same fingerprints keyed by text + size instead of position, used only while
     * the keyboard is shifting the layout (an item moved but didn't change).
     */
    private val pixelKeysBySize = HashMap<String, Pair<String, Int>>() // -> (key#hash, generation)

    private fun sizeKey(item: ScreenItem) = "${item.key}|${item.bounds.width()}x${item.bounds.height()}"

    /** Cache identity for an item, or null if it still needs a fingerprint. */
    private fun effectiveKey(item: ScreenItem): String? =
        if (item.textTrusted) item.key
        else pixelKeys[item.slot(scrollGeneration)] ?: keyboardShiftKey(item)

    /**
     * While the keyboard is shifting the layout, an item with the same text and
     * size as one fingerprinted just before (this or the previous generation)
     * is the same item that moved.
     */
    private fun keyboardShiftKey(item: ScreenItem): String? {
        if (!inKeyboardShift()) return null
        val (key, gen) = pixelKeysBySize[sizeKey(item)] ?: return null
        return key.takeIf { gen >= scrollGeneration - 1 }
    }

    // --- OCR ---------------------------------------------------------------
    /**
     * Screenshot the app window, fingerprint every item, and run OCR only if some
     * item hasn't been seen before. [force] always runs OCR (periodic re-sampling
     * of playing videos, whose on-screen text can change).
     */
    private fun requestOcr(force: Boolean = false) {
        if (Build.VERSION.SDK_INT < 34 || !ocr.available || ocr.busy) return
        val snap = lastSnapshot ?: return
        val wait = ocr.nextAllowedAt - SystemClock.uptimeMillis()
        if (wait > 0) {
            handler.removeCallbacks(ocrRunnable)
            handler.postDelayed(ocrRunnable, wait)
            return
        }
        ocr.capture(snap.windowId, snap.windowBounds) { shot ->
            if (shot == null) {
                // Screenshot failed: items stay covered, try again shortly.
                handler.removeCallbacks(ocrRunnable)
                handler.postDelayed(ocrRunnable, OCR_RETRY_MS)
                return@capture
            }
            if (snap.generation != scrollGeneration) {
                ocr.release(shot)
                schedule(0) // screen moved meanwhile; recompute will ask again
                return@capture
            }
            // Fresh heavily-blurred backdrop for the covered areas.
            cover?.setBlur(HeavyBlur.of(shot.bitmap), snap.windowBounds)
            blurGeneration = snap.generation
            // Fingerprint everything that isn't identified by text alone.
            val eff = HashMap<ScreenItem, String>()
            for (item in snap.items) {
                if (item in snap.containers) continue
                eff[item] = if (item.textTrusted) item.key else {
                    val h = PixelHash.of(shot.bitmap, shot.toBitmap(item.bounds)) ?: "none"
                    "${item.key}#$h".also {
                        pixelKeys[item.slot(snap.generation)] = it
                        pixelKeysBySize[sizeKey(item)] = it to snap.generation
                    }
                }
            }
            val unknown = eff.values.any { it !in ocrCleared && it !in ocrSpoiler }
            if (BuildConfig.DEBUG) android.util.Log.d(
                TAG, "capture: items=${eff.size} new=${eff.values.count { it !in ocrCleared && it !in ocrSpoiler }} " +
                    (if (!unknown && !force) "-> OCR skipped (all cached)" else "-> OCR run force=$force") +
                    " misses=" + eff.filter { it.value !in ocrCleared && it.value !in ocrSpoiler }
                    .map { (it, k) -> "${k.take(60)}@${it.bounds.toShortString()} text=[${it.judgeText.replace('\n', ' ').take(160)}]" }
            )
            if (!unknown && !force) {
                // Everything on screen was checked before: reveal without OCR.
                ocr.release(shot)
                schedule(0)
                return@capture
            }
            ocr.recognize(shot) { lines ->
                ocr.release(shot)
                if (lines == null) {
                    handler.removeCallbacks(ocrRunnable)
                    handler.postDelayed(ocrRunnable, OCR_RETRY_MS)
                    return@recognize
                }
                if (snap.generation == scrollGeneration) applyOcr(snap, eff, lines)
                schedule(0)
            }
        }
    }

    private fun applyOcr(snap: Snapshot, eff: Map<ScreenItem, String>, lines: List<OcrLine>) {
        val buckets = HashMap<ScreenItem, StringBuilder>()
        val loose = StringBuilder()
        for (line in lines) {
            val cx = line.bounds.centerX()
            val cy = line.bounds.centerY()
            val owner = snap.items
                .filter { it.bounds.contains(cx, cy) }
                .minByOrNull { it.bounds.width().toLong() * it.bounds.height() }
            (if (owner != null) buckets.getOrPut(owner) { StringBuilder() } else loose)
                .append(line.text).append('\n')
        }
        for (item in snap.items) {
            val key = eff[item] ?: continue
            ocrCleared.add(key)
            if (SpoilerRules.isSpoiler(buckets[item])) ocrSpoiler.add(key)
        }
        if (SpoilerRules.isSpoiler(loose)) looseOcrSpoilerGeneration = snap.generation
    }

    private class LruSet(private val max: Int) {
        private val map = object : LinkedHashMap<String, Unit>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?) = size > max
        }
        fun add(k: String) { map[k] = Unit }
        operator fun contains(k: String) = map.containsKey(k)
    }

    companion object {
        private const val TAG = "SpoilerFirewall"
        const val INSTAGRAM = "com.instagram.android"
        const val YOUTUBE = "com.google.android.youtube"
        val PROTECTED_PACKAGES = setOf(INSTAGRAM, YOUTUBE)

        private val EVERYTHING = Rect(Int.MIN_VALUE / 2, Int.MIN_VALUE / 2, Int.MAX_VALUE / 2, Int.MAX_VALUE / 2)
        private const val SETTLE_MS = 160L
        private const val CONTENT_DELAY_MS = 40L
        private const val PERIODIC_OCR_MS = 2500L
        private const val OCR_RETRY_MS = 700L
        private const val KEYBOARD_GRACE_MS = 800L
        private const val KEYBOARD_LOOKBACK_MS = 300L
    }
}
