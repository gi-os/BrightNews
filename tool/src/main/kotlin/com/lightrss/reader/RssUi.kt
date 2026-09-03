package com.lightrss.reader

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thelightphone.sdk.ui.LightTheme
import com.lightrss.reader.hw.WheelScroll
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightLazyScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import java.net.URI
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max

@Composable
fun ArticleList(
    articles: List<ArticleRow>,
    emptyMessage: String,
    onOpen: (ArticleRow) -> Unit,
    modifier: Modifier = Modifier,
    imageStore: ArticleImageStore? = null,
    listState: LazyListState = rememberLazyListState(),
    onEdge: ((direction: Int) -> Unit)? = null,
) {
    if (articles.isEmpty()) {
        EmptyState(emptyMessage, modifier)
        return
    }
    val rowHeight = articleRowHeightGridUnits()
    WheelScroll(listState, rowPx = rowHeight.gridUnitsAsDp().toPxHere(), onEdge = onEdge)
    LightLazyScrollView(
        modifier = modifier,
        listState = listState,
        uniformItemHeightGridUnits = rowHeight,
    ) {
        items(articles, key = { it.article.id }) { row ->
            ArticleListRow(row, onOpen, imageStore, rowHeight)
        }
    }
}

/**
 * The height, in grid units, of a list row holding [titleLines] lines of paragraph type over one
 * superfine line.
 *
 * These lists scroll in whole rows, so the scroll bar needs every row to be the same height, and
 * that height has to hold the tallest thing a row can contain. Measuring it from the type tokens
 * is not fussiness: text scales with the screen's height and a grid unit with its width, so the
 * two do not move together and a hand-tuned number is only ever right on the screen it was tuned
 * on. Both of these rows were tuned too short, and both of them clipped the same thing — the
 * small line underneath, which is the one naming the feed the article came from.
 */
@Composable
internal fun stackedRowHeightGridUnits(
    titleLines: Int,
    verticalPaddingUnits: Float,
    gapUnits: Float,
    minimumUnits: Float,
): Float {
    val density = LocalDensity.current
    val typography = LightThemeTokens.typography
    val gridUnit = 1f.gridUnitsAsDp()
    val content = with(density) {
        typography.paragraph.lineHeight.toDp() * titleLines +
            typography.superfine.lineHeight.toDp() +
            gridUnit * (gapUnits + 2 * verticalPaddingUnits)
    }
    return max(minimumUnits, content / gridUnit)
}

@Composable
private fun articleRowHeightGridUnits(): Float = stackedRowHeightGridUnits(
    titleLines = ARTICLE_TITLE_MAX_LINES,
    verticalPaddingUnits = ROW_VERTICAL_PADDING_UNITS,
    gapUnits = SOURCE_LINE_GAP_UNITS,
    minimumUnits = ARTICLE_ROW_MIN_HEIGHT,
)

@Composable
private fun ArticleListRow(
    row: ArticleRow,
    onOpen: (ArticleRow) -> Unit,
    imageStore: ArticleImageStore?,
    heightGridUnits: Float,
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
                top = ROW_VERTICAL_PADDING_UNITS.gridUnitsAsDp(),
                bottom = ROW_VERTICAL_PADDING_UNITS.gridUnitsAsDp(),
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LightText(
                    text = article.title,
                    variant = LightTextVariant.Paragraph,
                    lighten = article.isRead,
                    maxLines = ARTICLE_TITLE_MAX_LINES,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (article.isStarred) {
                    LightIcon(
                        icon = LightIcons.STAR,
                        size = 0.9f,
                        contentDescription = "Saved",
                        modifier = Modifier.padding(start = 0.5f.gridUnitsAsDp()),
                    )
                }
            }
            Row(modifier = Modifier.padding(top = SOURCE_LINE_GAP_UNITS.gridUnitsAsDp())) {
                LightText(
                    text = sourceLine(row),
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
        }
        // The picture sits at the edge, square and small, so the title keeps the width. A wide
        // thumbnail on the left pushed every headline into a narrow column.
        if (imageStore != null && article.imageUrl.isNotBlank()) {
            ArticleThumbnail(
                imageStore = imageStore,
                url = article.imageUrl,
                lighten = article.isRead,
                modifier = Modifier.padding(start = 1f.gridUnitsAsDp()),
            )
        }
    }
    HairlineDivider(Modifier.align(Alignment.BottomCenter))
    }
}

/** A dp value in pixels, at the current density. */
@Composable
private fun androidx.compose.ui.unit.Dp.toPxHere(): Float = with(LocalDensity.current) { toPx() }

/**
 * A one-pixel rule at a quarter strength, edge to edge. Rows and settings groups sit on these,
 * which is what lets the spacing between them be generous without the list turning into soup.
 */
@Composable
fun HairlineDivider(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(LightThemeTokens.colors.contentSecondary.copy(alpha = 0.25f)),
    )
}

/**
 * Article typography: the SDK's paragraph, with more air between the lines. Lists keep the
 * SDK's 1.25 leading, where a row is two lines and tighter is fine; a page of copy wants 1.5.
 */
@Composable
fun ReaderType(content: @Composable () -> Unit) {
    val typography = LightThemeTokens.typography
    val paragraph = typography.paragraph
    val relaxed = remember(typography) {
        typography.copy(
            paragraph = paragraph.copy(lineHeight = (paragraph.fontSize.value * READER_LINE_HEIGHT).sp),
            detail = typography.detail.copy(lineHeight = (typography.detail.fontSize.value * READER_LINE_HEIGHT).sp),
        )
    }
    LightTheme(colors = LightThemeTokens.colors, typography = relaxed, content = content)
}

/**
 * The line under the title: who wrote it, then where it came from.
 *
 * For a feed the two are usually the same thing, and the feed's own name is the useful half — a
 * byline that reads "BBC News" beside a title from BBC World tells you nothing.
 *
 * A newsletter is the other way round. Every issue in a label carries the same label, so showing
 * only that gives a screen of rows all claiming the same source; what actually varies, and what
 * you recognise, is the sender. So the sender leads and the label follows it, which also keeps the
 * label visible for anyone following more than one.
 */
private fun sourceLine(row: ArticleRow): String {
    val sender = row.article.author.trim()
    if (!NewsletterSync.isNewsletter(row.article.id) || sender.isEmpty()) return row.feedTitle
    if (sender.equals(row.feedTitle, ignoreCase = true)) return row.feedTitle
    return "$sender · ${row.feedTitle}"
}

@Composable
private fun ArticleThumbnail(
    imageStore: ArticleImageStore,
    url: String,
    lighten: Boolean,
    modifier: Modifier = Modifier,
) {
    val bitmap by rememberArticleImage(imageStore, url, ArticleImageStore.THUMBNAIL_WIDTH_PX)
    Box(
        modifier = modifier
            .width(THUMBNAIL_WIDTH.gridUnitsAsDp())
            .height(THUMBNAIL_HEIGHT.gridUnitsAsDp()),
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        if (image != null) {
            Image(
                bitmap = image.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alpha = if (lighten) 0.55f else 1f,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * Article body for the reader: the ordered text and image blocks a feed provided, falling back to
 * plain text for feeds that ship no images. [imageStore] of null renders text only.
 */
@Composable
fun ArticleBody(
    article: ArticleEntity,
    imageStore: ArticleImageStore?,
    modifier: Modifier = Modifier,
    onLink: ((ContentBlock.Link) -> Unit)? = null,
) {
    // The whole story, when the reader has fetched it, beats the paragraph the feed sent.
    val blocks = remember(article.id, article.contentBlocks, article.readerBlocks) {
        ContentBlocks.decode(article.readerBlocks.ifBlank { article.contentBlocks })
    }
    val text = article.content.ifBlank { article.summary }

    Column(modifier = modifier) {
        if (imageStore != null && article.imageUrl.isNotBlank() && !blocks.leadsWith(article.imageUrl)) {
            ArticleImage(imageStore, article.imageUrl)
        }
        // A body made only of images has nothing to show with images off, so fall back to the
        // text; a body with any text or link in it is shown as blocks whatever the setting.
        val showBlocks = blocks.any { it !is ContentBlock.Image } || (blocks.isNotEmpty() && imageStore != null)
        if (!showBlocks) {
            LightText(
                text = text.ifBlank { "This feed did not include article text." },
                variant = LightTextVariant.Paragraph,
                lighten = text.isBlank(),
                modifier = Modifier.padding(top = PARAGRAPH_GAP_UNITS.gridUnitsAsDp()),
            )
            return@Column
        }
        ContentBlocksBody(blocks, imageStore, onLink = onLink)
    }
}

/**
 * Renders ordered text, heading, link and image blocks. Images are skipped entirely when
 * [imageStore] is null, which is what Settings does when images are switched off. Links are
 * plain text when nothing is listening for them.
 */
@Composable
fun ContentBlocksBody(
    blocks: List<ContentBlock>,
    imageStore: ArticleImageStore?,
    modifier: Modifier = Modifier,
    onLink: ((ContentBlock.Link) -> Unit)? = null,
) {
    Column(modifier = modifier) {
        blocks.forEachIndexed { index, block ->
            // A block straight under its section heading sits close to it; everything else
            // gets a full paragraph gap.
            val afterHeading = index > 0 && blocks[index - 1] is ContentBlock.Heading
            val gap = if (afterHeading) AFTER_HEADING_GAP_UNITS else PARAGRAPH_GAP_UNITS
            when (block) {
                is ContentBlock.Text -> LightText(
                    text = block.text,
                    variant = LightTextVariant.Paragraph,
                    modifier = Modifier.padding(top = gap.gridUnitsAsDp()),
                )
                is ContentBlock.Heading -> Column(modifier = Modifier.padding(top = SECTION_GAP_UNITS.gridUnitsAsDp())) {
                    // A rule over the label, so a section reads as a section and not as a
                    // small grey word that happened to land between two paragraphs.
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(LightThemeTokens.colors.contentSecondary.copy(alpha = 0.35f)),
                    )
                    LightText(
                        text = block.text.uppercase(Locale.US),
                        variant = LightTextVariant.Fine,
                        lighten = true,
                        modifier = Modifier.padding(top = 0.6f.gridUnitsAsDp()),
                    )
                }
                is ContentBlock.Link -> {
                    val clickable = if (onLink != null) {
                        Modifier.lightClickable(onClickLabel = "Open ${block.text}", role = Role.Button) {
                            onLink(block)
                        }
                    } else {
                        Modifier
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(clickable)
                            .padding(top = (if (afterHeading) AFTER_HEADING_GAP_UNITS else 0.9f).gridUnitsAsDp()),
                    ) {
                        LightText(
                            text = block.text,
                            variant = LightTextVariant.Detail,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val host = sourceHost(block.url)
                        if (host.isNotBlank() && !block.text.equals(host, ignoreCase = true)) {
                            LightText(
                                text = host,
                                variant = LightTextVariant.Superfine,
                                lighten = true,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                is ContentBlock.Image -> if (imageStore != null) ArticleImage(imageStore, block.url)
            }
        }
    }
}

@Composable
private fun ArticleImage(imageStore: ArticleImageStore, url: String) {
    val bitmap by rememberArticleImage(imageStore, url, ArticleImageStore.READER_WIDTH_PX)
    val image = bitmap
    // Reserve space while loading so the text under the reader's thumb does not jump.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = PARAGRAPH_GAP_UNITS.gridUnitsAsDp())
            .then(if (image == null) Modifier.height(READER_IMAGE_PLACEHOLDER.gridUnitsAsDp()) else Modifier),
    ) {
        if (image != null) {
            Image(
                bitmap = image.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.FillWidth,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun List<ContentBlock>.leadsWith(url: String): Boolean =
    firstOrNull { it is ContentBlock.Image }?.let { it is ContentBlock.Image && it.url == url } == true


@Composable
fun FeedList(
    feeds: List<FeedRow>,
    onOpen: (FeedRow) -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    if (feeds.isEmpty()) {
        EmptyState("No subscriptions yet.\n\nTap + to add a website or feed.", modifier)
        return
    }
    // Measured, not hand-tuned: the same one-line-over-superfine stack that clipped in the
    // article and Gmail label lists, so it gets the same helper. The constant is only a floor.
    val rowHeight = stackedRowHeightGridUnits(
        titleLines = 1,
        verticalPaddingUnits = FEED_ROW_PADDING_UNITS,
        gapUnits = FEED_ROW_GAP_UNITS,
        minimumUnits = FEED_ROW_MIN_HEIGHT,
    )
    WheelScroll(listState, rowPx = rowHeight.gridUnitsAsDp().toPxHere())
    LightLazyScrollView(
        modifier = modifier,
        listState = listState,
        uniformItemHeightGridUnits = rowHeight,
    ) {
        items(feeds, key = { it.feed.id }) { row ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(rowHeight.gridUnitsAsDp())
                    .lightClickable(onClickLabel = "Open subscription", role = Role.Button) { onOpen(row) },
            ) {
            HairlineDivider(Modifier.align(Alignment.BottomCenter))
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        start = SIDE_MARGIN_UNITS.gridUnitsAsDp(),
                        end = ROW_END_MARGIN_UNITS.gridUnitsAsDp(),
                        top = FEED_ROW_PADDING_UNITS.gridUnitsAsDp(),
                        bottom = FEED_ROW_PADDING_UNITS.gridUnitsAsDp(),
                    ),
                verticalArrangement = Arrangement.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (row.feed.isFavorite) {
                        LightIcon(
                            icon = LightIcons.STAR,
                            size = 0.9f,
                            contentDescription = "Shown on home",
                            modifier = Modifier.padding(end = 0.5f.gridUnitsAsDp()),
                        )
                    }
                    LightText(
                        text = row.feed.title,
                        variant = LightTextVariant.Paragraph,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (row.unreadCount > 0) {
                        LightText(
                            text = row.unreadCount.toString(),
                            variant = LightTextVariant.Detail,
                            modifier = Modifier.padding(start = 1f.gridUnitsAsDp()),
                        )
                    }
                }
                val detail = when {
                    row.feed.errorMessage != null -> row.feed.errorMessage
                    row.articleCount == 1 -> "1 article"
                    else -> "${row.articleCount} articles"
                }
                LightText(
                    text = detail.orEmpty(),
                    variant = LightTextVariant.Superfine,
                    lighten = true,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = FEED_ROW_GAP_UNITS.gridUnitsAsDp()),
                )
            }
            }
        }
    }
}

@Composable
fun EmptyState(message: String, modifier: Modifier = Modifier) {
    // Set like content, not like an error: left-aligned at the reading margin, a few units
    // down from the bar, in full-strength type. The centred grey paragraph it replaces was the
    // least visible thing on the screen at the moment there was nothing else to look at.
    val parts = message.split("\n\n", limit = 2)
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = READER_MARGIN_UNITS.gridUnitsAsDp())
            .padding(top = 4f.gridUnitsAsDp()),
    ) {
        LightText(
            text = parts.first(),
            variant = LightTextVariant.Subheading,
        )
        if (parts.size > 1) {
            LightText(
                text = parts[1],
                variant = LightTextVariant.Paragraph,
                lighten = true,
                modifier = Modifier.padding(top = 1.25f.gridUnitsAsDp()),
            )
        }
    }
}

@Composable
fun StatusLine(message: String?, modifier: Modifier = Modifier) {
    if (message.isNullOrBlank()) return
    LightText(
        text = message,
        variant = LightTextVariant.Detail,
        lighten = true,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = SIDE_MARGIN_UNITS.gridUnitsAsDp(), vertical = 0.4f.gridUnitsAsDp()),
    )
}

@Composable
fun ConfirmationContent(
    message: String,
    confirmLabel: String,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LightThemeTokens.colors.background),
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 3f.gridUnitsAsDp()),
            contentAlignment = Alignment.Center,
        ) {
            LightText(
                text = message,
                variant = LightTextVariant.Paragraph,
                align = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
        LightBottomBar(
            items = listOf(
                LightBarButton.Text("CANCEL", onClick = onCancel),
                LightBarButton.Text(confirmLabel, onClick = onConfirm),
            ),
        )
    }
}

fun relativeTime(timestamp: Long, now: Long = System.currentTimeMillis()): String {
    val seconds = max(0L, (now - timestamp) / 1_000L)
    return when {
        seconds < 60 -> "NOW"
        seconds < 3_600 -> "${seconds / 60}M"
        seconds < 86_400 -> "${seconds / 3_600}H"
        seconds < 604_800 -> "${seconds / 86_400}D"
        else -> DateTimeFormatter.ofPattern("MMM d", Locale.US)
            .format(Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()))
            .uppercase(Locale.US)
    }
}

fun fullDate(timestamp: Long): String =
    DateTimeFormatter.ofPattern("MMM d, yyyy · h:mm a", Locale.US)
        .format(Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()))
        .uppercase(Locale.US)

fun sourceHost(url: String): String = runCatching {
    URI(url).host.orEmpty().removePrefix("www.").uppercase(Locale.US)
}.getOrDefault("")

/**
 * The spacing the whole app is set to.
 *
 * The first release set rows at 0.45 units of vertical padding and every edge at one unit, and
 * on a panel this size that reads as a spreadsheet: the only thing separating one row from the
 * next was proximity. These loosen it — fewer rows to a screen, on purpose; the wheel does the
 * scrolling — and the hairline under each row does the separating, so the space can be space.
 */
internal const val SIDE_MARGIN_UNITS = 1.5f
/** List rows end here: the scroll bar's gutter is already a margin, so the row uses the width. */
internal const val ROW_END_MARGIN_UNITS = 0.75f
internal const val READER_MARGIN_UNITS = 1.75f
internal const val ROW_PADDING_UNITS = 1.1f
internal const val PARAGRAPH_GAP_UNITS = 1.4f
internal const val SECTION_GAP_UNITS = 2.25f
internal const val AFTER_HEADING_GAP_UNITS = 0.5f

/** Body copy reads at this leading in the article, against the SDK's 1.25 in lists. */
internal const val READER_LINE_HEIGHT = 1.5f

private const val ARTICLE_TITLE_MAX_LINES = 2
private const val SOURCE_LINE_GAP_UNITS = 0.4f
private const val ROW_VERTICAL_PADDING_UNITS = 0.9f
private const val ARTICLE_ROW_MIN_HEIGHT = 6f
private const val THUMBNAIL_WIDTH = 3.6f
private const val THUMBNAIL_HEIGHT = 3.6f
private const val READER_IMAGE_PLACEHOLDER = 6f

// The subscriptions list: one line of paragraph type over a superfine detail line. The height
// itself is measured in stackedRowHeightGridUnits; this is only its floor.
private const val FEED_ROW_GAP_UNITS = 0.4f
private const val FEED_ROW_PADDING_UNITS = 1f
private const val FEED_ROW_MIN_HEIGHT = 5f
