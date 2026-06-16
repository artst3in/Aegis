package app.aether.aegis.call

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * In-call reactions. Tap an emoji on either side; both
 * peers see it float up the call surface, fade, and vanish. Wire shape
 * is a single status-class marker:
 *
 *   `[aegis:call-react:<emoji>]`
 *
 * Receiver-side parsing lives in [AegisApp.handleInboundStatus]; this
 * file owns the emit API + the in-process delivery flow + the floating
 * animation overlay.
 */
object CallReactions {
    /** Wire prefix for an outbound reaction STATUS message; closed with `]`. */
    const val PREFIX = "[aegis:call-react:"
    /** Inbound matcher; group 1 is the emoji. Receiver classifier uses this. */
    val MARKER = Regex("""\[aegis:call-react:(.+)]""")

    /** Hot stream of inbound + locally-emitted reactions, observed by
     *  [CallReactionsOverlay]. Bounded buffer with drop-oldest so a
     *  rapid emoji-spam doesn't pile up. */
    private val _events = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<String> = _events.asSharedFlow()

    /** Local emit — runs the animation on this device AND broadcasts
     *  to the peer in one call. Called from the in-call control bar. */
    fun emitAndSend(emoji: String, peerKey: String) {
        // Local echo first so our own reaction floats immediately, regardless
        // of whether the send succeeds (offline peer shouldn't stall the UX).
        _events.tryEmit(emoji)
        runCatching {
            // STATUS type: a sidechannel marker, not a chat message — it
            // doesn't persist as a conversation bubble on either side.
            app.aether.aegis.AegisApp.instance.protocolManager.sendMessage(
                to = peerKey,
                content = "$PREFIX$emoji]",
                type = app.aether.aegis.core.MessageType.STATUS,
            )
        }
    }

    /** Receiver-side — fed by the inbound STATUS classifier. */
    fun emitInbound(emoji: String) {
        _events.tryEmit(emoji)
    }
}

/** Reaction-emit row — the four picks that fit inside the call control
 *  bar. Compact: each emoji is a 36 dp tappable square, no labels. */
@Composable
fun CallReactionsRow(peerKey: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        listOf("❤️", "👍", "😂", "👏").forEach { emoji ->
            Surface(
                color = Color.Black.copy(alpha = 0.40f),
                shape = androidx.compose.foundation.shape.CircleShape,
                modifier = Modifier
                    .size(36.dp)
                    // Fire on press-down (not click) so a reaction feels
                    // instant; keyed on `emoji` so each square binds its own.
                    .pointerInput(emoji) {
                        awaitPointerEventScope {
                            while (true) {
                                val e = awaitPointerEvent()
                                if (e.changes.any { it.pressed }) {
                                    CallReactions.emitAndSend(emoji, peerKey)
                                    // Eat the press so the parent
                                    // doesn't also fire a click.
                                    e.changes.forEach { it.consume() }
                                    break
                                }
                            }
                        }
                    },
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Text(emoji, fontSize = 18.sp)
                }
            }
        }
    }
}

/** Full-screen overlay that consumes the [CallReactions.events] flow
 *  and floats each emoji up the screen from the bottom-centre with
 *  a fade. Stateless except for an internal list of live anim entries. */
@Composable
fun CallReactionsOverlay() {
    // Live animation entries. nanoTime key gives each a stable identity so
    // `key(...)` keeps animations independent even for identical emoji.
    val live = remember { mutableStateListOf<FloatingEmoji>() }
    LaunchedEffect(Unit) {
        CallReactions.events.collect { emoji ->
            val entry = FloatingEmoji(emoji = emoji, key = System.nanoTime())
            live += entry
        }
    }
    Box(modifier = Modifier.fillMaxSize()) {
        // Snapshot to a plain list before iterating: each child removes
        // itself via onDone, which would otherwise mutate `live` mid-iteration.
        live.toList().forEach { e ->
            key(e.key) { FloatingReaction(e, onDone = { live.remove(e) }) }
        }
    }
}

/** One in-flight floating emoji. [key] (nanoTime) is its animation identity. */
private data class FloatingEmoji(val emoji: String, val key: Long)

/**
 * Animate a single emoji from bottom-centre up the screen with a parallel
 * fade, then call [onDone] so the overlay can drop it. Two LaunchedEffects
 * run the rise and the fade concurrently over the same 2 s window.
 */
@Composable
private fun FloatingReaction(entry: FloatingEmoji, onDone: () -> Unit) {
    var translationY by remember { mutableStateOf(0f) }
    var alpha by remember { mutableStateOf(1f) }
    // Mild horizontal jitter so a flurry of taps doesn't render a
    // perfect column of identical emoji.
    val drift = remember { (-40..40).random().toFloat() }
    // Rise 600 px upward over 2 s; onDone runs only after the rise finishes
    // (this effect, not the fade, owns removal so the entry lives the full
    // animation even if the fade effect is recomposed away).
    LaunchedEffect(entry.key) {
        animate(
            initialValue = 0f,
            targetValue = -600f,
            animationSpec = tween(durationMillis = 2_000, easing = LinearEasing),
        ) { v, _ -> translationY = v }
        alpha = 0f
        onDone()
    }
    LaunchedEffect(entry.key) {
        animate(
            initialValue = 1f,
            targetValue = 0f,
            animationSpec = tween(durationMillis = 2_000, easing = LinearEasing),
        ) { v, _ -> alpha = v }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = 160.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Text(
            entry.emoji,
            fontSize = 36.sp,
            modifier = Modifier
                .alpha(alpha)
                .graphicsLayer {
                    this.translationY = translationY
                    this.translationX = drift
                },
        )
    }
}
