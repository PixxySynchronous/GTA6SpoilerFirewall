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

    /**
     * Text that only appears in this app's story viewer. While it's on screen, a tap
     * (left = previous, right = next slide) or a change of the story's text re-checks,
     * because moving between slides isn't a scroll. Empty = no story handling.
     */
    abstract val storyMarkers: List<String>

    /**
     * A screen showing ALL of these texts is exempt: never covered or checked, and
     * scrolling it triggers nothing (e.g. Instagram's DM inbox, which only lists
     * contacts). Tapping anything on it covers again before the next screen opens.
     * Empty = no exempt screen.
     */
    abstract val exemptScreenMarkers: List<String>

    /**
     * Full-screen pages that aren't stories (Reels) are judged on their text only
     * (caption, username, audio) with no picture scan, so they appear as fast as
     * YouTube Shorts do. Stories always keep the picture check.
     */
    abstract val fullPageTextOnly: Boolean

    /**
     * Screens showing this text never get the text-only rule above, even for posts
     * as tall as the screen (Instagram's Home feed: tall photo/video posts look like
     * Reels by size but must get the picture-text scan). Empty = no such screen.
     */
    abstract val fullPageScanScreenMarker: String

    object YouTube : AppProfile("com.google.android.youtube") {
        override val trustedTextLen = 25 // titles are specific
        override val neighbourContext = false
        override val ignoreZeroDeltaScrolls = false
        override val minScrollPx = 0
        override val openingGraceMs = 0L
        override val fullScreenToggle = true
        override val fullPageSwipeCoversAll = true
        override val storyMarkers = emptyList<String>()
        override val exemptScreenMarkers = emptyList<String>()
        override val fullPageTextOnly = false // Shorts are already fast (container rule)
        override val fullPageScanScreenMarker = ""
    }

    object Instagram : AppProfile("com.instagram.android") {
        override val trustedTextLen = 60 // "Photo by x" repeats; needs a full post's text
        override val neighbourContext = true
        override val ignoreZeroDeltaScrolls = true
        override val minScrollPx = 10
        override val openingGraceMs = 1500L
        override val fullScreenToggle = false
        override val fullPageSwipeCoversAll = true
        override val storyMarkers = listOf("Like Story", "Send message or reaction")
        // DM inbox (list of contacts/chats) only, recognised by the pencil button at
        // the top right ("New Message"), which stays while scrolling. Chats are still checked.
        override val exemptScreenMarkers = listOf("New Message")
        override val fullPageTextOnly = true // Reels as fast as Shorts
        // Invisible label at the top of every Home feed screen (never on the Reels tab):
        // feed posts always get the picture-text scan.
        override val fullPageScanScreenMarker = "Create Instagram Home Feed"
    }

    companion object {
        // Lazy: the companion is initialised together with the sealed class itself,
        // before the YouTube / Instagram objects exist.
        val ALL by lazy { listOf(YouTube, Instagram) }
        val PACKAGES by lazy { ALL.map { it.pkg }.toSet() }
        fun of(pkg: String?): AppProfile? = ALL.firstOrNull { it.pkg == pkg }
    }
}
