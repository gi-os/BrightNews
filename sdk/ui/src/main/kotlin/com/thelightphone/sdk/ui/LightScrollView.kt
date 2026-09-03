package com.thelightphone.sdk.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.alpha
import kotlinx.coroutines.delay
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private const val SCROLLBAR_WIDTH_UNITS = 2f
private const val SCROLLBAR_INSIDE_VERTICAL_PADDING_UNITS = 1f
private const val MIN_THUMB_FRACTION = 0.1f
private const val MAX_THUMB_FRACTION = 0.85f

enum class LightScrollBarPosition {
    Outside,

    Inside,
}

private data class LightScrollBarGeometry(
    val trackWidthPx: Float,
    val trackHeightPx: Float,
    val touchWidthPx: Float,
    val contentScrollOffsetPx: Float,
    val maxContentScrollOffsetPx: Float,
) {
    private val contentHeightPx = trackHeightPx + maxContentScrollOffsetPx
    private val visibleContentFraction = trackHeightPx / contentHeightPx
    private val contentScrollFraction = (contentScrollOffsetPx / maxContentScrollOffsetPx).coerceIn(0f, 1f)
    private val touchLeftPx = (trackWidthPx - touchWidthPx) / 2f
    private val touchRightPx = touchLeftPx + touchWidthPx

    val thumbHeightPx = trackHeightPx * visibleContentFraction.coerceIn(MIN_THUMB_FRACTION, MAX_THUMB_FRACTION)
    val maxThumbOffsetPx = trackHeightPx - thumbHeightPx
    val thumbOffsetPx = contentScrollFraction * maxThumbOffsetPx

    fun containsTouchX(xPx: Float): Boolean =
        xPx in touchLeftPx..touchRightPx

    fun containsThumb(xPx: Float, yPx: Float): Boolean =
        containsTouchX(xPx) &&
            yPx >= thumbOffsetPx &&
            yPx <= thumbOffsetPx + thumbHeightPx

    fun contentScrollOffsetToPlaceThumbTopAt(thumbTopPx: Float): Float {
        val fraction = (thumbTopPx / maxThumbOffsetPx).coerceIn(0f, 1f)
        return fraction * maxContentScrollOffsetPx
    }
}

/** How long the bar stays after the last movement before it fades. */
private const val SCROLLBAR_LINGER_MS = 900L
private const val SCROLLBAR_FADE_MS = 180

/**
 * The bar's opacity: up while the content is moving and for a moment after, then gone. The gutter
 * stays reserved, so nothing reflows — the bar simply appears when it has something to say.
 */
@Composable
private fun rememberScrollBarAlpha(scrollOffsetPx: Float): Float {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(scrollOffsetPx) {
        shown = true
        delay(SCROLLBAR_LINGER_MS)
        shown = false
    }
    val alpha by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(SCROLLBAR_FADE_MS),
        label = "scrollbar",
    )
    return alpha
}

fun scrollBarGutterUnits(position: LightScrollBarPosition): Float = when (position) {
    LightScrollBarPosition.Outside -> SCROLLBAR_WIDTH_UNITS
    LightScrollBarPosition.Inside -> 0f
}

fun scrollViewContentWidthUnits(totalWidthUnits: Float, position: LightScrollBarPosition): Float =
    totalWidthUnits - scrollBarGutterUnits(position)

@Composable
fun LightScrollView(
    modifier: Modifier = Modifier,
    scrollBarPosition: LightScrollBarPosition = LightScrollBarPosition.Outside,
    scrollState: ScrollState = rememberScrollState(),
    content: @Composable ColumnScope.() -> Unit,
) {
    val scope = rememberCoroutineScope()
    val scrollOffsetPx by remember { derivedStateOf { scrollState.value.toFloat() } }
    val showScrollBar = scrollState.maxValue > 0
    val contentPaddingEnd = scrollBarGutterUnits(scrollBarPosition)
    val barAlpha = rememberScrollBarAlpha(scrollOffsetPx)

    Box(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(end = contentPaddingEnd.gridUnitsAsDp())
                .verticalScroll(scrollState),
            content = content,
        )
        if (showScrollBar) {
            val verticalPadding = if (scrollBarPosition == LightScrollBarPosition.Inside) {
                SCROLLBAR_INSIDE_VERTICAL_PADDING_UNITS.gridUnitsAsDp()
            } else {
                0.dp
            }
            LightScrollBar(
                contentScrollOffsetPx = scrollOffsetPx,
                maxContentScrollOffsetPx = scrollState.maxValue.toFloat(),
                onScrollTo = { target ->
                    scope.launch { scrollState.scrollTo(target.roundToInt()) }
                },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .padding(vertical = verticalPadding)
                    .alpha(barAlpha),
            )
        }
    }
}

@Composable
fun LightLazyScrollView(
    modifier: Modifier = Modifier,
    scrollBarPosition: LightScrollBarPosition = LightScrollBarPosition.Outside,
    listState: LazyListState = rememberLazyListState(),
    uniformItemHeightGridUnits: Float,
    content: LazyListScope.() -> Unit,
) {
    val density = LocalDensity.current
    val itemHeightPx = with(density) { uniformItemHeightGridUnits.gridUnitsAsDp().toPx() }
    LightLazyScrollViewCore(
        modifier = modifier,
        scrollBarPosition = scrollBarPosition,
        listState = listState,
        offsetOf = { index -> index * itemHeightPx },
        totalOf = { count -> count * itemHeightPx },
        indexAt = { px, count -> if (itemHeightPx <= 0f) 0 else (px / itemHeightPx).toInt().coerceIn(0, (count - 1).coerceAtLeast(0)) },
        content = content,
    )
}

/**
 * A lazy list whose rows are not all the same height — a list with section headers between
 * its rows, say. [itemHeightGridUnits] answers for every index and must agree with what the
 * rows actually measure, the same contract as the uniform version, only per item. Heights are
 * prefix-summed once per [itemCount]/[heightsKey] change, so the bar's arithmetic stays O(1)
 * per frame.
 */
@Composable
fun LightLazyScrollView(
    modifier: Modifier = Modifier,
    scrollBarPosition: LightScrollBarPosition = LightScrollBarPosition.Outside,
    listState: LazyListState = rememberLazyListState(),
    itemCount: Int,
    heightsKey: Any?,
    itemHeightGridUnits: (index: Int) -> Float,
    content: LazyListScope.() -> Unit,
) {
    val density = LocalDensity.current
    val unitPx = with(density) { 1f.gridUnitsAsDp().toPx() }
    val prefix = remember(itemCount, heightsKey, unitPx) {
        val out = FloatArray(itemCount + 1)
        for (index in 0 until itemCount) out[index + 1] = out[index] + itemHeightGridUnits(index) * unitPx
        out
    }
    LightLazyScrollViewCore(
        modifier = modifier,
        scrollBarPosition = scrollBarPosition,
        listState = listState,
        offsetOf = { index -> prefix[index.coerceIn(0, prefix.size - 1)] },
        totalOf = { count -> prefix[count.coerceIn(0, prefix.size - 1)] },
        indexAt = { px, count ->
            var lo = 0
            var hi = (count - 1).coerceAtLeast(0).coerceAtMost(prefix.size - 2)
            while (lo < hi) {
                val mid = (lo + hi + 1) / 2
                if (prefix[mid] <= px) lo = mid else hi = mid - 1
            }
            lo
        },
        content = content,
    )
}

@Composable
private fun LightLazyScrollViewCore(
    modifier: Modifier,
    scrollBarPosition: LightScrollBarPosition,
    listState: LazyListState,
    offsetOf: (index: Int) -> Float,
    totalOf: (count: Int) -> Float,
    indexAt: (px: Float, count: Int) -> Int,
    content: LazyListScope.() -> Unit,
) {
    val scope = rememberCoroutineScope()

    val scrollMetrics by remember(offsetOf, totalOf) {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val itemCount = layoutInfo.totalItemsCount
            val viewportHeightPx = layoutInfo.viewportSize.height.toFloat()
            val totalContentPx = totalOf(itemCount)
            val maxScrollPx = (totalContentPx - viewportHeightPx).coerceAtLeast(0f)
            val scrollPx = (
                offsetOf(listState.firstVisibleItemIndex) +
                    listState.firstVisibleItemScrollOffset
                ).coerceAtMost(maxScrollPx)
            scrollPx to maxScrollPx
        }
    }
    val scrollPx = scrollMetrics.first
    val maxScrollPx = scrollMetrics.second
    val showScrollBar = maxScrollPx > 0f
    val barAlpha = rememberScrollBarAlpha(scrollPx)

    fun scrollToOffsetPx(targetPx: Float) {
        val itemCount = listState.layoutInfo.totalItemsCount
        if (itemCount == 0) return
        val clamped = targetPx.coerceIn(0f, maxScrollPx)
        val index = indexAt(clamped, itemCount)
        val offset = (clamped - offsetOf(index)).roundToInt().coerceAtLeast(0)
        scope.launch { listState.scrollToItem(index, offset) }
    }

    if (scrollBarPosition == LightScrollBarPosition.Inside) {
        Box(modifier = modifier) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                content = content,
            )
            if (showScrollBar) {
                LightScrollBar(
                    contentScrollOffsetPx = scrollPx,
                    maxContentScrollOffsetPx = maxScrollPx,
                    onScrollTo = ::scrollToOffsetPx,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .padding(
                            vertical = SCROLLBAR_INSIDE_VERTICAL_PADDING_UNITS.gridUnitsAsDp(),
                        )
                        .alpha(barAlpha),
                )
            }
        }
    } else {
        // The bar has its own column, so the list is not padded away from it a second time:
        // rows run right up to the gutter. The gutter is kept whether or not the bar is up, so
        // a list that grows past one screen does not shift its rows sideways.
        Row(modifier = modifier) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                content = content,
            )
            if (showScrollBar) {
                LightScrollBar(
                    contentScrollOffsetPx = scrollPx,
                    maxContentScrollOffsetPx = maxScrollPx,
                    onScrollTo = ::scrollToOffsetPx,
                    modifier = Modifier
                        .fillMaxHeight()
                        .alpha(barAlpha),
                )
            } else {
                Spacer(modifier = Modifier.width(SCROLLBAR_WIDTH_UNITS.gridUnitsAsDp()))
            }
        }
    }
}

@Composable
private fun LightScrollBar(
    contentScrollOffsetPx: Float,
    maxContentScrollOffsetPx: Float,
    onScrollTo: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val barColor = LightThemeTokens.colors.content
    val density = LocalDensity.current
    val trackWidth = SCROLLBAR_WIDTH_UNITS.gridUnitsAsDp()
    val railWidth = 1.dp
    val thumbWidth = 5.dp
    val touchWidth = thumbWidth * 6

    BoxWithConstraints(
        modifier = modifier.width(trackWidth),
        contentAlignment = Alignment.TopCenter,
    ) {
        val trackHeightPx = with(density) { maxHeight.toPx() }
        if (trackHeightPx <= 0f) return@BoxWithConstraints

        val geometry = LightScrollBarGeometry(
            trackWidthPx = with(density) { trackWidth.toPx() },
            trackHeightPx = trackHeightPx,
            touchWidthPx = with(density) { touchWidth.toPx() },
            contentScrollOffsetPx = contentScrollOffsetPx,
            maxContentScrollOffsetPx = maxContentScrollOffsetPx,
        )
        val thumbOffsetDp = with(density) { geometry.thumbOffsetPx.toDp() }
        val thumbHeightDp = with(density) { geometry.thumbHeightPx.toDp() }
        val currentOnScrollTo by rememberUpdatedState(onScrollTo)
        val currentGeometry by rememberUpdatedState(geometry)

        fun handleTrackTap(xPx: Float, yPx: Float) {
            val geometry = currentGeometry
            if (!geometry.containsTouchX(xPx)) return
            if (geometry.containsThumb(xPx, yPx)) return

            val targetThumbTopPx = yPx - geometry.thumbHeightPx / 2f
            currentOnScrollTo(geometry.contentScrollOffsetToPlaceThumbTopAt(targetThumbTopPx))
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val startGeometry = currentGeometry
                        if (!startGeometry.containsThumb(down.position.x, down.position.y)) {
                            return@awaitEachGesture
                        }

                        down.consume()
                        val dragStartThumbOffsetPx = startGeometry.thumbOffsetPx
                        var dragAmountPx = 0f

                        drag(down.id) { change ->
                            change.consume()
                            val geometry = currentGeometry

                            dragAmountPx += change.position.y - change.previousPosition.y
                            val newThumbTop = (dragStartThumbOffsetPx + dragAmountPx)
                                .coerceIn(0f, geometry.maxThumbOffsetPx)
                            currentOnScrollTo(geometry.contentScrollOffsetToPlaceThumbTopAt(newThumbTop))
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        handleTrackTap(offset.x, offset.y)
                    }
                },
        ) {
            Box(
                modifier = Modifier
                    .width(railWidth)
                    .fillMaxHeight()
                    .align(Alignment.Center)
                    .background(barColor),
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = thumbOffsetDp)
                    .width(trackWidth)
                    .height(thumbHeightDp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .width(thumbWidth)
                        .fillMaxHeight()
                        .background(barColor),
                )
            }
        }
    }
}

@Preview(widthDp = 1080 / 3, heightDp = 1240 / 3, showBackground = true)
@Composable
private fun PreviewLightScrollViewDark() {
    LightTheme(colors = LightThemeColors.Dark) {
        LightScrollView(
            modifier = Modifier
                .fillMaxSize()
                .background(color = LightThemeTokens.colors.background)
                .padding(
                    top = 1f.gridUnitsAsDp(),
                    start = 1f.gridUnitsAsDp(),
                    bottom = 1f.gridUnitsAsDp(),
                ),
            ) {
            repeat(24) { index ->
                LightText(
                    text = "Scrollable row ${index + 1}",
                    variant = LightTextVariant.Copy,
                    modifier = Modifier.padding(vertical = 0.75f.gridUnitsAsDp()),
                )
            }
        }
    }
}
