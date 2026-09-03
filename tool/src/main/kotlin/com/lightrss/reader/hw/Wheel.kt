package com.lightrss.reader.hw

import android.view.KeyEvent
import android.webkit.WebView
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.thelightphone.sdk.LightHardwareKeys
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlin.math.abs
import kotlin.math.sign

/**
 * Wheel notches on their way from the window to whatever is on screen.
 *
 * One notch per event, positive for up. Sibling apps hang this bus off their activity and hand it
 * down through a `CompositionLocal`, because they own the activity and can override
 * `dispatchKeyEvent` on it. A tool does not: `LightActivity` belongs to the SDK, hosts every
 * screen in one window, and the build rules keep tool code away from the activity, the window and
 * `LocalView` on purpose — there is nowhere here to put that override. The key therefore arrives
 * through `LightHardwareKeys`, and the bus is process-wide, because there is only ever one window
 * to feed it.
 *
 * A [SharedFlow] with no replay, deliberately: a notch that arrives while nothing is listening
 * is gone, which is what you want. Buffered generously because the sensor emits bursts far
 * faster than a frame.
 */
object Wheel {
    private val _notches = MutableSharedFlow<Int>(extraBufferCapacity = 64)
    val notches: SharedFlow<Int> = _notches.asSharedFlow()

    fun send(notches: Int) {
        _notches.tryEmit(notches)
    }
}

/**
 * Distance per notch. About six notches to a screenful on the LPIII panel — enough that a flick
 * of the wheel moves you somewhere, short enough that you can land on a paragraph.
 */
private val NOTCH = 64.dp

/**
 * Which way a notch moves the page.
 *
 * `1` means turning the wheel up moves you *down* the document — the wheel drags the page the
 * way a finger flick does, rather than moving a viewport over it. Flip to `-1` for the
 * mouse-wheel convention.
 */
private const val DIRECTION = 1

/**
 * Fraction of the remaining distance applied per frame.
 *
 * This is the whole reason scrolling feels like scrolling rather than like a slide projector.
 * The sensor fires a notch every ~35 ms, which is faster than a frame, so applying each one on
 * arrival produces a stack of instant jumps — nothing to follow with your eye. Instead every
 * notch adds to a debt, and each frame pays off a share of it, so one notch glides and a fast
 * spin becomes a single continuous sweep that keeps moving slightly after your thumb stops.
 *
 * 0.36 settles ~90% inside five frames (~85 ms): quick enough that the page moves with the thumb,
 * slow enough that a spin still reads as one sweep. 0.28 was a touch behind the hand.
 */
private const val SMOOTHING = 0.36f

/**
 * How long a turn stays live, for the acceleration's sense of "the last notch".
 *
 * There is no longer any guard on the first notch. The first release held it until a second
 * arrived, which lost every deliberate single click; the next held it for 150 ms, which kept the
 * click but made every first turn feel late. A stray brush of the wheel costs one paragraph of
 * scroll, and that is cheaper than a wheel that hesitates.
 */
private const val IDLE_MS = 1_500L

/**
 * Acceleration: what a notch is worth, by how soon it followed the last one.
 *
 * A flat 64 dp a notch means six notches to a screen whether you are nudging a paragraph into
 * view or trying to get past forty source links. A notch that lands within 45 ms of the last is
 * a flick and covers two and a half times the distance; one that arrives after a pause is a
 * single deliberate click and covers exactly one. The steps are coarse on purpose — the sensor
 * fires at ~35–60 ms intervals when spun, so there are only a few speeds to tell apart.
 */
private fun gainFor(gapMs: Long): Float = when {
    gapMs < 45 -> 2.6f
    gapMs < 90 -> 1.7f
    gapMs < 180 -> 1.2f
    else -> 1f
}

/**
 * Notches past an edge before the screen is asked to turn the page.
 *
 * At the top or bottom the debt is unpayable and dropped, so turning further did nothing. Three
 * more notches in the same direction is a clear "there is no more of this, give me the next
 * one", and is what [WheelScroll]'s `onEdge` reports. One would fire on the overshoot of an
 * ordinary glide; three is a decision.
 */
private const val EDGE_NOTCHES = 3

/** The reference frame for [SMOOTHING], so a dropped frame does not halve the glide. */
private const val FRAME_MS = 1_000f / 60f

/**
 * Take the wheel for this screen.
 *
 * Call once near the top of any screen that has something to scroll. The SDK hands the key over
 * before the view hierarchy is walked, which is what lets a notch beat a focused WebView or text
 * field.
 *
 * Both DOWN and UP are consumed: one notch is a complete DOWN+UP pair, and letting the UP through
 * means a text field can receive it as a keypress.
 *
 * Installed once and left in place. Screens come and go inside one window here, so handing the
 * keys back on every navigation would only open a gap where a turn does nothing; anything that is
 * not the wheel is passed straight on, and a notch that arrives with no scroller listening is
 * dropped by the bus.
 */
@Composable
fun WheelKeys() {
    DisposableEffect(Unit) {
        if (LightHardwareKeys.handler !== wheelKeys) LightHardwareKeys.handler = wheelKeys
        onDispose { }
    }
}

/**
 * Held in a `val` rather than written as a method reference at the call site, so the identity
 * check in [WheelKeys] compares the same object each time.
 */
private val wheelKeys: (KeyEvent) -> Boolean = { event ->
    when (LightKeys.of(event)) {
        LightKey.WheelUp -> {
            if (event.action == KeyEvent.ACTION_DOWN) Wheel.send(1)
            true
        }

        LightKey.WheelDown -> {
            if (event.action == KeyEvent.ACTION_DOWN) Wheel.send(-1)
            true
        }

        null -> false
    }
}

/**
 * What the wheel is doing at the ends of the content, for a screen that wants to react to it.
 * `edge` is `+1` while the last turn ran off the bottom, `-1` off the top, `0` otherwise.
 */
class WheelEdge {
    var edge by mutableIntStateOf(0)
        internal set
}

/**
 * Point the wheel at a Compose scroller. Works for both `ScrollState` and `LazyListState`.
 *
 * [rowPx], for a list of uniform rows, makes the glide settle on a row boundary — the lazy list
 * promises whole rows and the wheel used to stop with one cut in half. [onEdge] is called with the
 * direction after [EDGE_NOTCHES] further notches at an end of the content; the returned
 * [WheelEdge] says when that end has been reached so the screen can say what a further turn does.
 */
@Composable
fun WheelScroll(
    state: ScrollableState,
    active: Boolean = true,
    rowPx: Float? = null,
    onEdge: ((direction: Int) -> Unit)? = null,
): WheelEdge {
    val step = with(LocalDensity.current) { NOTCH.toPx() }
    val debt = remember { Debt() }
    val wake = remember { Channel<Unit>(Channel.CONFLATED) }
    val edge = remember { WheelEdge() }
    val edgeHandler by rememberUpdatedState(onEdge)

    ArmedNotches(active) { notches, gapMs ->
        val direction = notches.sign * DIRECTION
        // At an edge, further turning is a request, not a scroll.
        if (debt.edge != 0 && direction == debt.edge) {
            debt.edgeCount += abs(notches)
            if (debt.edgeCount >= EDGE_NOTCHES) {
                val at = debt.edge
                // Whatever the screen does next — a new article, the next category — starts
                // from a clean slate, or its first notches would count as more edge.
                debt.edgeCount = 0
                debt.edge = 0
                edge.edge = 0
                edgeHandler?.invoke(at)
            }
            return@ArmedNotches
        }
        if (direction != debt.edge) {
            debt.edge = 0
            debt.edgeCount = 0
            edge.edge = 0
        }
        debt.px += notches * step * gainFor(gapMs) * DIRECTION
        wake.trySend(Unit)
    }

    LaunchedEffect(state, wake) {
        while (true) {
            // Suspends while the wheel is still, so an idle screen costs nothing.
            wake.receive()
            // One scroll session for the whole glide. A finger on the screen takes priority and
            // cancels this, which is the right outcome.
            state.scroll {
                var last = 0L
                var hitEdge = 0
                while (abs(debt.px) > 0.5f) {
                    val now = withFrameNanos { it }
                    val dt = if (last == 0L) FRAME_MS else (now - last) / 1_000_000f
                    last = now
                    val wanted = (debt.px * smoothingFor(dt)).let {
                        // Never stall a notch out in sub-pixel increments.
                        if (abs(it) < 1f) debt.px else it
                    }
                    debt.px -= wanted
                    val consumed = scrollBy(wanted)
                    // At the top or bottom the rest of the debt is unpayable, and keeping it
                    // would mean the next turn back spends its first notches on nothing.
                    if (abs(consumed) < abs(wanted) - 0.5f) {
                        hitEdge = wanted.sign.toInt()
                        debt.px = 0f
                    }
                }
                if (hitEdge != 0) {
                    debt.edge = hitEdge
                    debt.edgeCount = 0
                    edge.edge = hitEdge
                } else if (rowPx != null && state is LazyListState && state.canScrollForward && state.canScrollBackward) {
                    // Land on a row. Not at either end: the last screenful of a list rarely
                    // ends on a row boundary, and snapping there pulled the list back up
                    // against the direction of travel — a jump the wrong way, right at the
                    // bottom. Short and eased like the glide, so it reads as its end.
                    val offset = state.firstVisibleItemScrollOffset.toFloat()
                    var snap = if (offset > rowPx / 2f) rowPx - offset else -offset
                    var frames = 0
                    while (abs(snap) > 0.5f && frames < 8) {
                        withFrameNanos { }
                        val part = (snap * 0.5f).let { if (abs(it) < 1f) snap else it }
                        snap -= part
                        if (abs(scrollBy(part)) < abs(part) - 0.5f) break
                        frames++
                    }
                }
            }
        }
    }
    return edge
}

/** The same, for the sign-in WebView, which Compose knows nothing about. */
@Composable
fun WheelScroll(web: WebView?, active: Boolean = true) {
    val step = with(LocalDensity.current) { NOTCH.toPx() }
    val debt = remember { Debt() }
    val wake = remember { Channel<Unit>(Channel.CONFLATED) }

    ArmedNotches(active && web != null) { notches, gapMs ->
        debt.px += notches * step * gainFor(gapMs) * DIRECTION
        wake.trySend(Unit)
    }

    LaunchedEffect(web, wake) {
        val target = web ?: return@LaunchedEffect
        while (true) {
            wake.receive()
            var last = 0L
            while (abs(debt.px) > 0.5f) {
                val now = withFrameNanos { it }
                val dt = if (last == 0L) FRAME_MS else (now - last) / 1_000_000f
                last = now
                val wanted = (debt.px * smoothingFor(dt)).let {
                    if (abs(it) < 1f) debt.px else it
                }
                debt.px -= wanted
                if (!target.wheelScrollBy(wanted.toInt())) debt.px = 0f
            }
        }
    }
}

/** Notches as they arrive, with the gap since the previous one for the acceleration. */
@Composable
private fun ArmedNotches(active: Boolean, onNotch: (notches: Int, gapMs: Long) -> Unit) {
    val handler by rememberUpdatedState(onNotch)
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        val incoming = Channel<Int>(Channel.UNLIMITED)
        launch { Wheel.notches.collect { incoming.send(it) } }
        var last = 0L
        while (true) {
            val notches = incoming.receive()
            val t = System.nanoTime() / 1_000_000
            val gap = if (last == 0L || t - last > IDLE_MS) IDLE_MS else t - last
            last = t
            handler(notches, gap)
        }
    }
}

/** Per-frame share of the debt, corrected for the frame's real length. */
private fun smoothingFor(dtMs: Float): Float {
    val frames = (dtMs / FRAME_MS).coerceIn(0.5f, 4f)
    return 1f - Math.pow((1f - SMOOTHING).toDouble(), frames.toDouble()).toFloat()
}

/**
 * Scrolling the document, bounded at both ends. Returns false at an edge, so the caller can drop
 * the rest of the debt instead of pushing against it.
 *
 * `canScrollVertically` is the public way to ask — `computeVerticalScrollRange` is protected on
 * View, and the content height is only available in CSS pixels that would have to be scaled back
 * by hand.
 */
private fun WebView.wheelScrollBy(px: Int): Boolean {
    if (px == 0) return true
    if (!canScrollVertically(if (px > 0) 1 else -1)) return false
    scrollBy(0, px)
    return true
}

/**
 * Distance still owed to the scroller.
 *
 * Deliberately not Compose state: nothing in composition reads it, and making it observable would
 * restart the glide on every recomposition it caused.
 */
private class Debt {
    @Volatile
    var px: Float = 0f

    /** Which end the last glide ran into, `0` when it did not. */
    @Volatile
    var edge: Int = 0

    /** Notches turned into that edge since it was reached. */
    @Volatile
    var edgeCount: Int = 0
}
