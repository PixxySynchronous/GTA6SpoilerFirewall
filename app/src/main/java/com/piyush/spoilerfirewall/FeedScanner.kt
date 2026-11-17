package com.piyush.spoilerfirewall

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

enum class ItemKind {
    /** A child of a scrolling list: a feed card, Short, Reel, search row, chip... */
    FEED,
    /** A leaf outside any list that has its own text (buttons, tabs, titles). */
    LABELLED,
    /** A leaf outside any list with no text (video surface, images). */
    UNLABELLED
}

class ScreenItem(
    /** Identity from the item's OWN text (stable while scrolling), or its position if it has none. */
    val key: String,
    val bounds: Rect,
    /** Text the spoiler rules judge (on Instagram: own text + neighbouring rows). */
    val judgeText: String,
    val kind: ItemKind,
    /**
     * True when [key] alone identifies the content well enough to reuse a past
     * OCR result (a long, specific YouTube title). Otherwise the identity also
     * needs a pixel fingerprint of the item.
     */
    val textTrusted: Boolean = false
) {
    /** Unique per position + scroll generation; used to attach a pixel fingerprint. */
    fun slot(generation: Int) = "$key@${bounds.flattenToString()}@g$generation"
}

/**
 * Splits an app window's accessibility tree into "items" that can be cleared
 * one by one. Anything not covered by a cleared item stays hidden.
 */
class FeedScanner {
    private var visited = 0

    private var trustedTextLen = Int.MAX_VALUE
    private var keyPrefix = ""
    private var windowArea = 0L

    /**
     * @param trustedTextLen how long an item's own text must be to identify its
     *        content on its own. YouTube titles are specific at 25 chars; on
     *        Instagram short labels like "Photo by bbcnews" repeat across
     *        different photos, so it needs a full post's text (60+ chars).
     */
    fun scan(
        root: AccessibilityNodeInfo,
        windowBounds: Rect,
        neighbourContext: Boolean,
        generation: Int,
        trustedTextLen: Int,
        keyPrefix: String
    ): List<ScreenItem> {
        visited = 0
        this.trustedTextLen = trustedTextLen
        this.keyPrefix = keyPrefix
        windowArea = windowBounds.width().toLong() * windowBounds.height()
        val out = ArrayList<ScreenItem>()
        walk(root, windowBounds, neighbourContext, generation, out, 0)
        return out
    }

    private fun trusted(own: String) = own.trim().length >= trustedTextLen

    private fun walk(
        node: AccessibilityNodeInfo,
        clip: Rect,
        neighbourContext: Boolean,
        generation: Int,
        out: MutableList<ScreenItem>,
        depth: Int
    ) {
        if (depth > MAX_DEPTH || ++visited > MAX_NODES) return
        if (!node.isVisibleToUser) return
        val bounds = Rect().also(node::getBoundsInScreen)
        if (!bounds.intersect(clip)) return

        val childCount = node.childCount
        if (node.isScrollable && childCount > 0) {
            val children = (0 until childCount)
                .mapNotNull { node.getChild(it) }
                .filter { it.isVisibleToUser }
            val texts = children.map { subtreeText(it) }
            children.forEachIndexed { i, child ->
                if (containsScrollable(child, 0)) {
                    // e.g. a horizontal Shorts shelf inside the vertical feed
                    walk(child, bounds, neighbourContext, generation, out, depth + 1)
                    return@forEachIndexed
                }
                val cb = Rect().also(child::getBoundsInScreen)
                if (!cb.intersect(bounds) || cb.isEmpty) return@forEachIndexed
                // Instagram splits one post into several rows (header / media /
                // caption), so a row is judged together with its neighbours.
                val judge = if (neighbourContext) {
                    listOfNotNull(texts.getOrNull(i - 1), texts[i], texts.getOrNull(i + 1))
                        .joinToString("\n")
                } else texts[i]
                val own = texts[i]
                out += ScreenItem(
                    // Own text only: neighbours change while scrolling, which used to
                    // make the same post look new and re-run OCR.
                    key = "$keyPrefix|F|${child.className}|${own.hashCode()}|${own.length}",
                    bounds = cb,
                    judgeText = judge,
                    kind = ItemKind.FEED,
                    textTrusted = trusted(own)
                )
            }
            return
        }

        if (childCount == 0) {
            val own = ownText(node)
            out += if (own.isBlank()) {
                // No text: identity comes entirely from its pixel fingerprint, and it
                // is judged by the text right around it (e.g. the miniplayer's title).
                ScreenItem("$keyPrefix|U|${node.className}", bounds, contextFor(node), ItemKind.UNLABELLED)
            } else {
                ScreenItem("$keyPrefix|L|${node.className}|${own.hashCode()}", bounds, own, ItemKind.LABELLED, trusted(own))
            }
            return
        }

        for (i in 0 until childCount) {
            node.getChild(i)?.let { walk(it, bounds, neighbourContext, generation, out, depth + 1) }
        }
    }

    /**
     * Text of the nearest small ancestor that has some (the miniplayer bar with
     * its title, a card with its caption). Stops at ancestors covering half the
     * window or more, so a picture is never "cleared" by a whole page's text.
     * Returns "" if there's no such local context.
     */
    private fun contextFor(node: AccessibilityNodeInfo): String {
        var p = node.parent
        var depth = 0
        while (p != null && depth < 6) {
            val r = Rect().also(p::getBoundsInScreen)
            if (r.width().toLong() * r.height() >= windowArea / 2) return ""
            val t = subtreeText(p)
            if (t.trim().length >= 15) return t
            p = p.parent
            depth++
        }
        return ""
    }

    private fun containsScrollable(node: AccessibilityNodeInfo, depth: Int): Boolean {
        if (depth > 25) return false
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            if (c.isScrollable || containsScrollable(c, depth + 1)) return true
        }
        return false
    }

    private fun subtreeText(node: AccessibilityNodeInfo): String {
        val sb = StringBuilder()
        var count = 0
        fun rec(n: AccessibilityNodeInfo, d: Int) {
            if (d > 30 || ++count > 400 || sb.length > MAX_TEXT) return
            val t = ownText(n)
            if (t.isNotBlank()) sb.append(t).append('\n')
            for (i in 0 until n.childCount) n.getChild(i)?.let { rec(it, d + 1) }
        }
        rec(node, 0)
        return sb.toString()
    }

    private fun ownText(node: AccessibilityNodeInfo): String {
        val parts = listOfNotNull(node.text, node.contentDescription)
            .map { it.toString() }
            .filter { it.isNotBlank() }
        return parts.joinToString(" ")
    }

    private companion object {
        const val MAX_DEPTH = 80
        const val MAX_NODES = 2500
        const val MAX_TEXT = 1200
    }
}
