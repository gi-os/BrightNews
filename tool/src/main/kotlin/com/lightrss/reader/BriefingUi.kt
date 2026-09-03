package com.lightrss.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lightrss.reader.hw.WheelScroll
import com.thelightphone.sdk.ui.LightLazyScrollView
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import java.time.ZoneId
import java.util.Locale

/* ====================================================================== briefing */

/**
 * The Daily Briefing: today, then the edition.
 *
 * A plain scroll view rather than the lazy list — the page mixes a dozen row shapes and is a
 * few screens long at most, so laziness buys nothing and the uniform-row scroll bar would fit
 * none of it.
 */
@Composable
fun BriefingContent(
    today: NotebookDay?,
    edition: List<Briefing.CategoryStories>,
    expanded: Set<Long>,
    onToggle: (Long) -> Unit,
    onOpen: (ArticleRow) -> Unit,
    scroll: ScrollState,
    modifier: Modifier = Modifier,
) {
    val now = System.currentTimeMillis()
    val zone = ZoneId.systemDefault()
    val nowMinute = java.time.Instant.ofEpochMilli(now).atZone(zone).let { it.hour * 60 + it.minute }
    WheelScroll(scroll)
    LightScrollView(
        modifier = modifier.fillMaxWidth(),
        scrollState = scroll,
    ) {
        // The mock's 20 px at 360 wide is 1.5 units on both sides; the gutter is outside that.
        Column(
            modifier = Modifier.padding(
                start = SIDE_MARGIN_UNITS.gridUnitsAsDp(),
                end = SIDE_MARGIN_UNITS.gridUnitsAsDp(),
                bottom = 3f.gridUnitsAsDp(),
            ),
        ) {
            LightText(
                text = Briefing.dateLine(now, zone),
                variant = LightTextVariant.Heading,
                modifier = Modifier.padding(top = 0.6f.gridUnitsAsDp()),
            )
            Briefing.weatherLine(today?.weather)?.let { line ->
                LightText(
                    text = line,
                    variant = LightTextVariant.Detail,
                    lighten = true,
                )
            }

            SectionLabel("Your day", topUnits = 1.05f)
            val entries = today?.entries.orEmpty()
            if (entries.isEmpty()) {
                LightText(
                    text = "Nothing on the calendar.",
                    variant = LightTextVariant.Detail,
                    lighten = true,
                    modifier = Modifier.padding(top = 0.6f.gridUnitsAsDp()),
                )
            } else {
                val calendarOpen = HomeViewModel.CALENDAR_SECTION in expanded
                val shown = if (calendarOpen) entries else entries.take(TOP_N)
                Column(modifier = Modifier.padding(top = 0.3f.gridUnitsAsDp())) {
                    shown.forEach { entry -> CalendarRow(entry, past = !entry.allDay && entry.endMinute in 0 until nowMinute) }
                }
                MoreRow(
                    hidden = entries.size - TOP_N,
                    open = calendarOpen,
                    noun = "MORE",
                    onClick = { onToggle(HomeViewModel.CALENDAR_SECTION) },
                )
            }

            if (edition.isEmpty()) {
                Column(modifier = Modifier.padding(top = 3f.gridUnitsAsDp())) {
                    LightText("No Kagi categories yet.", LightTextVariant.Subheading)
                    LightText(
                        text = "Open the list button to follow World, Tech, your city…",
                        variant = LightTextVariant.Paragraph,
                        lighten = true,
                        modifier = Modifier.padding(top = 1.25f.gridUnitsAsDp()),
                    )
                }
            }
            edition.forEach { category ->
                SectionLabel(
                    text = category.title,
                    topUnits = 1.35f,
                    trailing = "${category.stories.size} ${if (category.stories.size == 1) "STORY" else "STORIES"}",
                )
                val open = category.feedId in expanded
                val shown = if (open) category.stories else category.stories.take(TOP_N)
                shown.forEachIndexed { index, row ->
                    if (index > 0) HairlineDivider()
                    StoryRow(rank = index + 1, row = row, onOpen = onOpen)
                }
                MoreRow(
                    hidden = category.stories.size - TOP_N,
                    open = open,
                    noun = "MORE STORIES",
                    onClick = { onToggle(category.feedId) },
                )
            }
        }
    }
}

/** A rule and a small uppercase label, the section treatment the reader already uses. */
@Composable
private fun SectionLabel(text: String, topUnits: Float, trailing: String? = null) {
    Column(modifier = Modifier.padding(top = topUnits.gridUnitsAsDp())) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(LightThemeTokens.colors.contentSecondary.copy(alpha = 0.35f)),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 0.6f.gridUnitsAsDp()),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            LightText(text.uppercase(Locale.US), LightTextVariant.Fine, lighten = true)
            if (trailing != null) LightText(trailing.uppercase(Locale.US), LightTextVariant.Fine, lighten = true)
        }
    }
}

/**
 * `SHOW 9 MORE STORIES` / `SHOW FEWER` under a section cut to its first three. Nothing when
 * the section was short enough to show whole.
 */
@Composable
private fun MoreRow(hidden: Int, open: Boolean, noun: String, onClick: () -> Unit) {
    if (hidden <= 0) return
    Column {
        HairlineDivider()
        LightText(
            text = if (open) "SHOW FEWER" else "SHOW $hidden $noun",
            variant = LightTextVariant.Fine,
            lighten = true,
            modifier = Modifier
                .fillMaxWidth()
                .lightClickable(onClickLabel = if (open) "Show fewer" else "Show $hidden more", role = Role.Button) { onClick() }
                .padding(vertical = 0.75f.gridUnitsAsDp()),
        )
    }
}

/**
 * `9:30   Dentist` — time in a fixed column, all-day items with a dash. An entry already over
 * is lightened, so the eye lands on what is still to come.
 */
@Composable
private fun CalendarRow(entry: NotebookEntry, past: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 0.45f.gridUnitsAsDp()),
        verticalAlignment = Alignment.Top,
    ) {
        LightText(
            text = Briefing.entryTime(entry),
            variant = LightTextVariant.Paragraph,
            lighten = true,
            maxLines = 1,
            modifier = Modifier.width(3.9f.gridUnitsAsDp()),
        )
        LightText(
            text = entry.title,
            variant = LightTextVariant.Paragraph,
            lighten = past,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/** A ranked story: number in the margin, title, then `11 SOURCES` and the age. */
@Composable
private fun StoryRow(rank: Int, row: ArticleRow, onOpen: (ArticleRow) -> Unit) {
    val article = row.article
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(
                onClickLabel = if (article.isRead) "Open story" else "Open unread story",
                role = Role.Button,
            ) { onOpen(row) }
            .padding(top = 0.9f.gridUnitsAsDp(), bottom = 0.8f.gridUnitsAsDp()),
        verticalAlignment = Alignment.Top,
    ) {
        LightText(
            text = rank.toString(),
            variant = LightTextVariant.Detail,
            lighten = true,
            modifier = Modifier
                .width(1.65f.gridUnitsAsDp())
                .padding(top = 0.15f.gridUnitsAsDp()),
        )
        Column(modifier = Modifier.weight(1f)) {
            LightText(
                text = article.title,
                variant = LightTextVariant.Paragraph,
                lighten = article.isRead,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 0.4f.gridUnitsAsDp()),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                LightText(
                    text = if (article.sourceCount > 0) "${article.sourceCount} SOURCES" else "",
                    variant = LightTextVariant.Superfine,
                    lighten = true,
                    maxLines = 1,
                )
                LightText(
                    text = relativeTime(article.publishedAt),
                    variant = LightTextVariant.Superfine,
                    lighten = true,
                )
            }
        }
    }
}

/* ====================================================================== timeline */

/**
 * RSS and newsletters in one stream, cut into time buckets.
 *
 * Two row shapes in one lazy list — a bucket header and a story — so the scroll bar is told the
 * height of every index rather than one height for all. Stories scroll in whole rows on the
 * wheel; headers are short enough that a snap landing on one still reads right.
 */
@Composable
fun TimelineList(
    items: List<Briefing.TimelineItem>,
    emptyMessage: String,
    onOpen: (ArticleRow) -> Unit,
    modifier: Modifier = Modifier,
    imageStore: ArticleImageStore? = null,
    listState: LazyListState = rememberLazyListState(),
) {
    if (items.isEmpty()) {
        EmptyState(emptyMessage, modifier)
        return
    }
    // Rows are as tall as their headline needs — one line or two — the way the mock draws
    // them, rather than every row reserving two lines. The scroll bar needs each height ahead
    // of layout, so the headline is measured here with the same type the row will use; a
    // couple of hundred measurements once per list change is nothing.
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val typography = LightThemeTokens.typography
    val measurer = rememberTextMeasurer()
    val unitPx = with(density) { 1f.gridUnitsAsDp().toPx() }
    val heights = remember(items, unitPx, configuration.screenWidthDp, typography, imageStore != null) {
        val textWidthPx = with(density) {
            (configuration.screenWidthDp.dp.toPx() - (GUTTER_UNITS + SIDE_MARGIN_UNITS + ROW_END_MARGIN_UNITS) * unitPx)
        }
        val thumbPx = (THUMB_UNITS + 1f) * unitPx
        val superfineLh = with(density) { typography.superfine.lineHeight.toPx() }
        val paragraphLh = with(density) { typography.paragraph.lineHeight.toPx() }
        items.mapIndexed { index, item ->
            when (item) {
                is Briefing.TimelineItem.Header -> if (index == 0) HEADER_FIRST_UNITS else HEADER_UNITS
                is Briefing.TimelineItem.Story -> {
                    val hasThumb = imageStore != null && item.row.article.imageUrl.isNotBlank()
                    val width = (textWidthPx - if (hasThumb) thumbPx else 0f).toInt().coerceAtLeast(1)
                    val lines = measurer.measure(
                        text = item.row.article.title,
                        style = typography.paragraph,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        constraints = Constraints(maxWidth = width),
                    ).lineCount.coerceIn(1, 2)
                    val textPx = superfineLh + STORY_GAP_UNITS * unitPx + lines * paragraphLh
                    val px = maxOf(textPx, if (hasThumb) THUMB_UNITS * unitPx else 0f) + 2 * STORY_PAD_UNITS * unitPx
                    px / unitPx
                }
            }
        }
    }
    WheelScroll(listState)
    LightLazyScrollView(
        modifier = modifier,
        listState = listState,
        itemCount = items.size,
        heightsKey = heights,
        itemHeightGridUnits = { heights[it] },
    ) {
        items(items.size, key = { items[it].key }) { index ->
            when (val item = items[index]) {
                is Briefing.TimelineItem.Header -> BucketHeader(item.label, heights[index])
                is Briefing.TimelineItem.Story -> TimelineRow(
                    row = item.row,
                    onOpen = onOpen,
                    imageStore = imageStore,
                    heightGridUnits = heights[index],
                    // No rule between a header and its first story; the header is the break.
                    divider = index + 1 < items.size && items[index + 1] is Briefing.TimelineItem.Story,
                )
            }
        }
    }
}

@Composable
private fun BucketHeader(label: String, heightUnits: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(heightUnits.gridUnitsAsDp())
            .padding(start = SIDE_MARGIN_UNITS.gridUnitsAsDp(), end = ROW_END_MARGIN_UNITS.gridUnitsAsDp()),
        contentAlignment = Alignment.BottomStart,
    ) {
        LightText(label, LightTextVariant.Fine, lighten = true, maxLines = 1)
    }
}

/** Source line over the headline, age at the right, thumbnail at the edge. */
@Composable
private fun TimelineRow(
    row: ArticleRow,
    onOpen: (ArticleRow) -> Unit,
    imageStore: ArticleImageStore?,
    heightGridUnits: Float,
    divider: Boolean,
) {
    val article = row.article
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(heightGridUnits.gridUnitsAsDp())
            .lightClickable(
                onClickLabel = if (article.isRead) "Open article" else "Open unread article",
                role = Role.Button,
            ) { onOpen(row) },
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = SIDE_MARGIN_UNITS.gridUnitsAsDp(),
                    end = ROW_END_MARGIN_UNITS.gridUnitsAsDp(),
                    top = STORY_PAD_UNITS.gridUnitsAsDp(),
                    bottom = STORY_PAD_UNITS.gridUnitsAsDp(),
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center,
            ) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    LightText(
                        text = Briefing.sourceLine(row),
                        variant = LightTextVariant.Superfine,
                        lighten = true,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 0.5f.gridUnitsAsDp()),
                    )
                    LightText(
                        text = relativeTime(article.publishedAt),
                        variant = LightTextVariant.Superfine,
                        lighten = true,
                    )
                }
                LightText(
                    text = article.title,
                    variant = LightTextVariant.Paragraph,
                    lighten = article.isRead,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = STORY_GAP_UNITS.gridUnitsAsDp()),
                )
            }
            if (imageStore != null && article.imageUrl.isNotBlank()) {
                ArticleThumbnail(
                    imageStore = imageStore,
                    url = article.imageUrl,
                    lighten = article.isRead,
                    modifier = Modifier.padding(start = 1f.gridUnitsAsDp()),
                )
            }
        }
        if (divider) HairlineDivider(Modifier.align(Alignment.BottomCenter))
    }
}

private const val TOP_N = 3
private const val HEADER_UNITS = 3f
/** The mock's 12 px / 5 px at 360 wide, in units. */
private const val STORY_PAD_UNITS = 0.9f
private const val STORY_GAP_UNITS = 0.375f
/** Must agree with `RssUi`'s thumbnail and the SDK's scroll-bar gutter. */
private const val THUMB_UNITS = 3.6f
private const val GUTTER_UNITS = 2f
private const val HEADER_FIRST_UNITS = 2.4f
