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
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
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
) {
    if (articles.isEmpty()) {
        EmptyState(emptyMessage, modifier)
        return
    }
    LightLazyScrollView(
        modifier = modifier,
        uniformItemHeightGridUnits = ARTICLE_ROW_HEIGHT,
    ) {
        items(articles, key = { it.article.id }) { row ->
            ArticleListRow(row, onOpen, imageStore)
        }
    }
}

@Composable
private fun ArticleListRow(
    row: ArticleRow,
    onOpen: (ArticleRow) -> Unit,
    imageStore: ArticleImageStore?,
) {
    val article = row.article
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ARTICLE_ROW_HEIGHT.gridUnitsAsDp())
            .lightClickable(
                onClickLabel = if (article.isRead) "Open article" else "Open unread article",
                role = Role.Button,
            ) { onOpen(row) }
            .padding(horizontal = 1f.gridUnitsAsDp(), vertical = 0.45f.gridUnitsAsDp()),
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
                    maxLines = 3,
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
            Row(modifier = Modifier.padding(top = 0.25f.gridUnitsAsDp())) {
                LightText(
                    text = row.feedTitle,
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
        if (imageStore != null && article.imageUrl.isNotBlank()) {
            ArticleThumbnail(
                imageStore = imageStore,
                url = article.imageUrl,
                lighten = article.isRead,
                modifier = Modifier.padding(start = 0.75f.gridUnitsAsDp()),
            )
        }
    }
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
) {
    val blocks = remember(article.id, article.contentBlocks) {
        ContentBlocks.decode(article.contentBlocks)
    }
    val text = article.content.ifBlank { article.summary }

    Column(modifier = modifier) {
        if (imageStore != null && article.imageUrl.isNotBlank() && !blocks.leadsWith(article.imageUrl)) {
            ArticleImage(imageStore, article.imageUrl)
        }
        if (blocks.isEmpty() || imageStore == null) {
            LightText(
                text = text.ifBlank { "This feed did not include article text." },
                variant = LightTextVariant.Paragraph,
                lighten = text.isBlank(),
                modifier = Modifier.padding(top = 1f.gridUnitsAsDp()),
            )
            return@Column
        }
        blocks.forEach { block ->
            when (block) {
                is ContentBlock.Text -> LightText(
                    text = block.text,
                    variant = LightTextVariant.Paragraph,
                    modifier = Modifier.padding(top = 1f.gridUnitsAsDp()),
                )
                is ContentBlock.Image -> ArticleImage(imageStore, block.url)
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
            .padding(top = 1f.gridUnitsAsDp())
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
) {
    if (feeds.isEmpty()) {
        EmptyState("No subscriptions yet.\n\nTap + to add a website or feed.", modifier)
        return
    }
    LightLazyScrollView(
        modifier = modifier,
        uniformItemHeightGridUnits = FEED_ROW_HEIGHT,
    ) {
        items(feeds, key = { it.feed.id }) { row ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(FEED_ROW_HEIGHT.gridUnitsAsDp())
                    .lightClickable(onClickLabel = "Open subscription", role = Role.Button) { onOpen(row) }
                    .padding(horizontal = 1f.gridUnitsAsDp()),
                verticalArrangement = Arrangement.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
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
                            variant = LightTextVariant.Superfine,
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
                    modifier = Modifier.padding(top = 0.25f.gridUnitsAsDp()),
                )
            }
        }
    }
}

@Composable
fun EmptyState(message: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 3f.gridUnitsAsDp()),
        contentAlignment = Alignment.Center,
    ) {
        LightText(
            text = message,
            variant = LightTextVariant.Paragraph,
            align = androidx.compose.ui.text.style.TextAlign.Center,
            lighten = true,
        )
    }
}

@Composable
fun StatusLine(message: String?, modifier: Modifier = Modifier) {
    if (message.isNullOrBlank()) return
    LightText(
        text = message,
        variant = LightTextVariant.Superfine,
        lighten = true,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 1f.gridUnitsAsDp(), vertical = 0.25f.gridUnitsAsDp()),
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

private const val ARTICLE_ROW_HEIGHT = 6.1f
private const val THUMBNAIL_WIDTH = 6.8f
private const val THUMBNAIL_HEIGHT = 5.2f
private const val READER_IMAGE_PLACEHOLDER = 8f
private const val FEED_ROW_HEIGHT = 3.6f
