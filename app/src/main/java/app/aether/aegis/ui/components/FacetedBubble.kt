package app.aether.aegis.ui.components

import androidx.compose.foundation.shape.GenericShape
import app.aether.aegis.core.Message
import kotlin.math.max
import kotlin.math.min

/*
 * Faceted chat-bubble geometry — the LunaGlass message slab.
 *
 * Bubbles are OCTAGONS (a rectangle with 45° corner facets), not pills:
 * the symmetric cut rhymes with the hexagon avatars/buttons without
 * pretending to be a hexagon (a tall block can't be one). 45° is chosen
 * deliberately — a shallower (hex-parallel 60°) facet reads as a rounded
 * corner and makes the block look barrel-bloated, because the eye can't
 * distinguish a near-vertical facet from a fillet.
 *
 * Consecutive messages from the same sender COLLAPSE into one slab:
 *   - only the FIRST message of a run keeps its top facets,
 *   - only the LAST keeps its bottom facets AND the speech tail,
 *   - middle messages are plain rectangles, butted seamlessly (a 1px
 *     border overlap reads as a hairline divider between them).
 * So a run of N messages is a single faceted column capped by one tail,
 * not N separate bubbles.
 */

/** 45° corner facet size (each cut is this in BOTH x and y → symmetric). */
const val FACET_CUT_DP: Float = 14f
/** Speech-tail dimensions: protrusion depth, base width, inset from the
 *  bubble's near corner (mirrors the Comms tab icon's tail). */
const val FACET_TAIL_H_DP: Float = 13f
const val FACET_TAIL_W_DP: Float = 16f
// Tail sits inboard of the bottom facet → MUST exceed FACET_CUT_DP.
const val FACET_TAIL_INSET_DP: Float = 22f

/**
 * Build the bubble outline for one message in a run. All distances are
 * PIXELS (the caller converts dp via the local density — GenericShape's
 * lambda has no density of its own).
 *
 * [topCut]    — facet the top corners (first-of-run / standalone).
 * [bottomTail]— facet the bottom corners AND draw the tail (last-of-run /
 *               standalone). When set, the caller must reserve [tailH] of
 *               extra height below the content so the tail has room.
 * [outgoing]  — tail on the bottom-RIGHT (your messages) vs bottom-LEFT.
 *
 * A message that is neither first nor last of its run passes both flags
 * false → a plain rectangle that seams into its neighbours.
 */
fun facetedBubbleShape(
    cut: Float,
    tailH: Float,
    tailW: Float,
    inset: Float,
    outgoing: Boolean,
    topCut: Boolean,
    bottomTail: Boolean,
): GenericShape = GenericShape { size, _ ->
    val w = size.width
    val h = size.height
    // Body bottom — the tail lives in the strip below it.
    val bb = if (bottomTail) h - tailH else h

    // top-left
    if (topCut) moveTo(cut, 0f) else moveTo(0f, 0f)
    // top edge → top-right (facet if first-of-run)
    if (topCut) {
        lineTo(w - cut, 0f); lineTo(w, cut)
    } else {
        lineTo(w, 0f)
    }
    // right edge down + bottom edge (with tail/facets only on last-of-run)
    if (bottomTail) {
        // ALL FOUR corners are faceted (uniform octagon); the tail tucks
        // INBOARD of the bottom facet on its side. Requires inset > cut so
        // the tail base clears the facet.
        if (outgoing) {
            // tail bottom-right
            lineTo(w, bb - cut)
            lineTo(w - cut, bb)                     // bottom-right facet
            lineTo(w - inset, bb)
            lineTo(w - inset, bb + tailH)          // apex
            lineTo(w - inset - tailW, bb)
            lineTo(cut, bb)
            lineTo(0f, bb - cut)                    // bottom-left facet
        } else {
            // tail bottom-left
            lineTo(w, bb - cut)
            lineTo(w - cut, bb)                     // bottom-right facet
            lineTo(inset + tailW, bb)
            lineTo(inset, bb + tailH)               // apex
            lineTo(inset, bb)
            lineTo(cut, bb)
            lineTo(0f, bb - cut)                    // bottom-left facet
        }
    } else {
        lineTo(w, bb)   // bb == h here
        lineTo(0f, bb)
    }
    // left edge up + close
    if (topCut) lineTo(0f, cut) else lineTo(0f, 0f)
    close()
}

/** Per-message placement within its same-sender run. */
data class BubbleRunMeta(
    val firstOfRun: Boolean,
    val lastOfRun: Boolean,
    /** Uniform bubble width for the whole run, in px (every message in a
     *  run shares the run's widest line, capped at the measure). */
    val widthPx: Float,
)

/**
 * Group a conversation's messages into same-sender runs and compute each
 * message's [BubbleRunMeta]. A run breaks when the sender changes OR the
 * day changes (so a run never spans a date separator).
 *
 * [naturalWidthPx] measures a message's preferred bubble width (text width
 * + padding, or a full-width value for attachments); the run takes the
 * MAX across its members, capped at [capPx], so the run is a clean
 * monoblock with no per-line saw-tooth.
 */
fun computeRunMeta(
    messages: List<Message>,
    dayOf: (Message) -> String,
    naturalWidthPx: (Message) -> Float,
    capPx: Float,
): Map<String, BubbleRunMeta> {
    // BURN bubbles render as their own standalone reveal-on-tap item, so
    // they break a run. CALL_LOG rows DO merge into same-sender runs now —
    // they're side-aligned call bubbles (your calls right, theirs left), so
    // they group like any message. Keep this in lockstep with
    // ChatScreen.buildChatRenders.
    fun breaksRun(m: Message) =
        m.type == app.aether.aegis.core.MessageType.BURN
    val out = HashMap<String, BubbleRunMeta>(messages.size)
    var i = 0
    while (i < messages.size) {
        if (breaksRun(messages[i])) {
            // Self-contained: full facets + tail + natural width.
            out[messages[i].id] = BubbleRunMeta(
                firstOfRun = true,
                lastOfRun = true,
                widthPx = min(naturalWidthPx(messages[i]), capPx),
            )
            i++
            continue
        }
        val from = messages[i].from
        val day = dayOf(messages[i])
        var j = i
        var widest = 0f
        while (j < messages.size && messages[j].from == from &&
            dayOf(messages[j]) == day && !breaksRun(messages[j])
        ) {
            widest = max(widest, naturalWidthPx(messages[j]))
            j++
        }
        val w = min(widest, capPx)
        for (k in i until j) {
            out[messages[k].id] = BubbleRunMeta(
                firstOfRun = (k == i),
                lastOfRun = (k == j - 1),
                widthPx = w,
            )
        }
        i = j
    }
    return out
}
