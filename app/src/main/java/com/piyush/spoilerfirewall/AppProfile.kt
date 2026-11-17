package com.piyush.spoilerfirewall

/**
 * Everything that is specific to one protected app lives here, so tuning
 * Instagram can never change how YouTube behaves (and vice versa). The
 * service asks [AppProfile.of] for the app an event / window belongs to.
 */
sealed class AppProfile(val pkg: String) {

    /** Own-text length at which an item is identified by text alone (no pixel fingerprint). */
    abstract val trustedTextLen: Int

    /** Judge each list row together with its neighbours (post split into header/media/caption rows). */
    abstract val neighbourContext: Boolean

    /**
     * Ignore "scrolled" reports that moved 0 px. Some lists report a scroll after
     * every re-layout (post loaded, video started) without the user scrolling.
     */
    abstract val ignoreZeroDeltaScrolls: Boolean

    /** Scrolls that moved fewer pixels than this (layout jitter) don't trigger a check. */
    abstract val minScrollPx: Int

    /** After the app opens, extra "new screen" reports within this time don't restart the check. */
    abstract val openingGraceMs: Long

    /** Treat the full-screen button / rotation as a re-layout of the same content. */
    abstract val fullScreenToggle: Boolean

    /** On a full-screen page (Reel / Short / story), any swipe re-covers the whole screen. */
    abstract val fullPageSwipeCoversAll: Boolean

    object YouTube : AppProfile("com.google.android.youtube") {
        override val trustedTextLen = 25 // titles are specific
        override val neighbourContext = false
        override val ignoreZeroDeltaScrolls = false
        override val minScrollPx = 0
        override val openingGraceMs = 0L
        override val fullScreenToggle = true
        override val fullPageSwipeCoversAll = true
    }

    object Instagram : AppProfile("com.instagram.android") {
        override val trustedTextLen = 60 // "Photo by x" repeats; needs a full post's text
        override val neighbourContext = true
        override val ignoreZeroDeltaScrolls = true
        override val minScrollPx = 10
        override val openingGraceMs = 1500L
        override val fullScreenToggle = false
        override val fullPageSwipeCoversAll = true
    }

    companion object {
        // Lazy: the companion is initialised together with the sealed class itself,
        // before the YouTube / Instagram objects exist.
        val ALL by lazy { listOf(YouTube, Instagram) }
        val PACKAGES by lazy { ALL.map { it.pkg }.toSet() }
        fun of(pkg: String?): AppProfile? = ALL.firstOrNull { it.pkg == pkg }
    }
}
