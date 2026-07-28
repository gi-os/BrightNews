package com.lightrss.reader

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.net.URI

/** A fetched web page and the address it finally resolved to after any redirects. */
data class PageDocument(val html: String, val url: String)

sealed interface FeedLoadResult {
    data class Loaded(
        val feed: ParsedFeed,
        val feedUrl: String,
        val etag: String?,
        val lastModified: String?,
    ) : FeedLoadResult

    data object NotModified : FeedLoadResult
}

data class SyncState(
    val isRefreshing: Boolean = false,
    val completedFeeds: Int = 0,
    val totalFeeds: Int = 0,
    val lastFinishedAt: Long = 0,
    val message: String? = null,
)

class RssApi {
    private val client = HttpClient(OkHttp) {
        expectSuccess = false
        install(HttpTimeout) {
            requestTimeoutMillis = 25_000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 20_000
        }
    }

    suspend fun load(url: String, etag: String? = null, lastModified: String? = null): FeedLoadResult {
        val normalized = normalizeUrl(url)
        val response = request(normalized, etag, lastModified)
        if (response.status == HttpStatusCode.NotModified) return FeedLoadResult.NotModified
        ensureSuccess(response)
        val body = response.bodyAsText()
        val effectiveUrl = response.call.request.url.toString()

        val looksLikeFeed = body.take(2_000).lowercase().let { head ->
            "<rss" in head || "<feed" in head || "<rdf:rdf" in head
        }
        if (looksLikeFeed) {
            return FeedLoadResult.Loaded(
                feed = RssParser.parse(body, effectiveUrl),
                feedUrl = effectiveUrl,
                etag = response.headers[HttpHeaders.ETag],
                lastModified = response.headers[HttpHeaders.LastModified],
            )
        }

        val discovered = RssParser.discoverFeedUrl(body, effectiveUrl)
            ?: throw IllegalArgumentException("No RSS or Atom feed was found at this address.")
        val feedResponse = request(discovered, null, null)
        ensureSuccess(feedResponse)
        val feedBody = feedResponse.bodyAsText()
        val effectiveFeedUrl = feedResponse.call.request.url.toString()
        return FeedLoadResult.Loaded(
            feed = RssParser.parse(feedBody, effectiveFeedUrl),
            feedUrl = effectiveFeedUrl,
            etag = feedResponse.headers[HttpHeaders.ETag],
            lastModified = feedResponse.headers[HttpHeaders.LastModified],
        )
    }

    /**
     * Fetches an article page as HTML for reader mode, following redirects to wherever the link
     * actually lands. Sites that turn away the Light RSS user agent are retried once as a desktop
     * browser, which is what the reader is standing in for.
     */
    suspend fun loadPage(url: String): PageDocument {
        val normalized = normalizeUrl(url)
        var response = pageRequest(normalized, USER_AGENT)
        if (response.status.value in BLOCKED_STATUSES) {
            response = pageRequest(normalized, BROWSER_USER_AGENT)
        }
        if (response.status.value in BLOCKED_STATUSES) {
            throw IllegalStateException(
                "${response.status.value}: this site refuses outside readers. " +
                    "It may need a subscription.",
            )
        }
        ensureSuccess(response)

        val contentType = response.headers[HttpHeaders.ContentType].orEmpty()
        if (contentType.isNotBlank() && !contentType.contains("html", ignoreCase = true)) {
            throw IllegalArgumentException("That link is not a web page.")
        }
        val declaredLength = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        if (declaredLength != null && declaredLength > MAX_PAGE_BYTES) {
            throw IllegalArgumentException("That page is too large to read here.")
        }
        return PageDocument(
            html = response.bodyAsText().take(MAX_PAGE_CHARS),
            url = response.call.request.url.toString(),
        )
    }

    private suspend fun pageRequest(url: String, userAgent: String): HttpResponse =
        client.get(url) {
            header(HttpHeaders.UserAgent, userAgent)
            header(HttpHeaders.Accept, "text/html,application/xhtml+xml;q=0.9,*/*;q=0.5")
            header(HttpHeaders.AcceptLanguage, "en-US,en;q=0.9")
        }

    /** Fetches raw image bytes for the reader. Returns null for anything that is not an image. */
    suspend fun loadImageBytes(url: String): ByteArray? {
        val response = client.get(url) {
            header(HttpHeaders.UserAgent, USER_AGENT)
            header(HttpHeaders.Accept, "image/*")
        }
        if (!response.status.isSuccess()) return null
        val contentType = response.headers[HttpHeaders.ContentType].orEmpty()
        if (contentType.isNotBlank() && !contentType.startsWith("image/", ignoreCase = true)) return null
        val declaredLength = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        if (declaredLength != null && declaredLength > MAX_IMAGE_BYTES) return null
        val bytes = response.body<ByteArray>()
        return bytes.takeIf { it.size <= MAX_IMAGE_BYTES }
    }

    fun close() = client.close()

    private suspend fun request(url: String, etag: String?, lastModified: String?): HttpResponse =
        client.get(url) {
            header(HttpHeaders.UserAgent, USER_AGENT)
            header(HttpHeaders.Accept, "application/rss+xml, application/atom+xml, application/xml, text/xml, text/html;q=0.8")
            if (!etag.isNullOrBlank()) header(HttpHeaders.IfNoneMatch, etag)
            if (!lastModified.isNullOrBlank()) header(HttpHeaders.IfModifiedSince, lastModified)
        }

    private fun ensureSuccess(response: HttpResponse) {
        if (!response.status.isSuccess()) {
            throw IllegalStateException("The server returned HTTP ${response.status.value}.")
        }
    }

    companion object {
        const val USER_AGENT = "LightRSS/1.1 (Light Phone III)"

        /** Images larger than this are skipped rather than buffered into memory. */
        const val MAX_IMAGE_BYTES = 8L * 1024 * 1024

        /**
         * Used only for reader-mode page fetches, and only after the honest one is turned away.
         * Feed requests always identify as Light RSS.
         */
        const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"

        private val BLOCKED_STATUSES = setOf(401, 403, 406, 429, 451)
        private const val MAX_PAGE_BYTES = 5L * 1024 * 1024
        private const val MAX_PAGE_CHARS = 2_000_000

        fun normalizeUrl(raw: String): String {
            val candidate = raw.trim().let { value ->
                if ("://" in value) value else "https://$value"
            }
            val uri = runCatching { URI(candidate) }
                .getOrElse { throw IllegalArgumentException("Please enter a valid web or feed address.") }
            val host = uri.host.orEmpty()
            if (uri.scheme !in setOf("http", "https") || host.isBlank()) {
                throw IllegalArgumentException("Only http and https feed addresses are supported.")
            }
            if ('.' !in host && ':' !in host && host != "localhost") {
                throw IllegalArgumentException("Enter a complete website or feed address.")
            }
            return uri.normalize().toString()
        }
    }
}

class RssRepository(
    private val dao: RssDao,
    private val api: RssApi = RssApi(),
    imageCacheDir: File? = null,
) {
    /** Null when the caller did not provide a cache directory, e.g. in tests. */
    val images: ArticleImageStore? = imageCacheDir?.let { root ->
        ArticleImageStore(File(root, "images"), api::loadImageBytes)
    }

    private val syncMutex = Mutex()
    private val _syncState = MutableStateFlow(SyncState())
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    fun observeInbox(unreadOnly: Boolean): Flow<List<ArticleRow>> =
        if (unreadOnly) dao.observeUnread() else dao.observeInbox()

    fun observeStarred(): Flow<List<ArticleRow>> = dao.observeStarred()
    fun observeFeeds(): Flow<List<FeedRow>> = dao.observeFeeds()
    fun observeFeed(feedId: Long): Flow<FeedEntity?> = dao.observeFeed(feedId)
    fun observeFeedArticles(feedId: Long): Flow<List<ArticleRow>> = dao.observeFeedArticles(feedId)
    fun observeArticle(articleId: String): Flow<ArticleRow?> = dao.observeArticle(articleId)
    fun search(query: String): Flow<List<ArticleRow>> = dao.observeSearch(query.trim())

    /** Whether feed images should be downloaded and shown. Defaults to on. */
    val imagesEnabled: Flow<Boolean> = dao.observeMetadata(SHOW_IMAGES_KEY)
        .map { it != "0" }
        .distinctUntilChanged()

    suspend fun setImagesEnabled(enabled: Boolean) {
        dao.putMetadata(AppMetadataEntity(SHOW_IMAGES_KEY, if (enabled) "1" else "0"))
        if (!enabled) images?.clear()
    }

    fun clearImageCache() = images?.clear()

    private val readerPages = mutableMapOf<String, ReaderPage>()
    private val readerLock = Mutex()

    /**
     * Reader-mode version of an article's linked page. Cached per session so paging back and
     * forth does not re-fetch, and so the page stays readable once it has been downloaded.
     */
    suspend fun readerPage(articleId: String, url: String, refresh: Boolean = false): ReaderPage {
        if (url.isBlank()) throw IllegalArgumentException("This article has no link to open.")
        if (!refresh) {
            readerLock.withLock { readerPages[articleId] }?.let { return it }
        }

        var document = api.loadPage(url)

        // Feeds link through redirectors and tracking variants. Take the page's word for where
        // the real article lives before deciding there is nothing to read.
        val hop = ReaderExtractor.metaRefreshUrl(document.html, document.url)
            ?: ReaderExtractor.canonicalUrl(document.html, document.url)
        if (hop != null) {
            runCatching { api.loadPage(hop) }.getOrNull()?.let { document = it }
        }

        var page = ReaderExtractor.extract(document.html, document.url)

        // Some publishers keep a lighter AMP copy that a text reader can actually use.
        if (!with(ReaderExtractor) { page.hasContent() }) {
            ReaderExtractor.ampUrl(document.html, document.url)?.let { amp ->
                runCatching { api.loadPage(amp) }.getOrNull()?.let { ampDocument ->
                    val ampPage = ReaderExtractor.extract(ampDocument.html, ampDocument.url)
                    if (with(ReaderExtractor) { ampPage.hasContent() }) page = ampPage
                }
            }
        }

        readerLock.withLock {
            if (readerPages.size >= MAX_CACHED_READER_PAGES) readerPages.clear()
            readerPages[articleId] = page
        }
        return page
    }

    suspend fun refreshAll(force: Boolean = true) {
        if (!syncMutex.tryLock()) return
        try {
            val feeds = dao.getFeeds()
            if (!force && feeds.isNotEmpty() && feeds.all { System.currentTimeMillis() - it.lastFetchedAt < AUTO_REFRESH_AGE_MS }) {
                return
            }
            _syncState.value = SyncState(isRefreshing = true, totalFeeds = feeds.size)
            var failures = 0
            feeds.forEachIndexed { index, feed ->
                if (refreshFeedInternal(feed).isFailure) failures += 1
                _syncState.value = SyncState(
                    isRefreshing = true,
                    completedFeeds = index + 1,
                    totalFeeds = feeds.size,
                )
            }
            _syncState.value = SyncState(
                isRefreshing = false,
                completedFeeds = feeds.size,
                totalFeeds = feeds.size,
                lastFinishedAt = System.currentTimeMillis(),
                message = when {
                    feeds.isEmpty() -> "Add a feed to begin."
                    failures == 0 -> "Up to date"
                    failures == 1 -> "1 feed could not refresh"
                    else -> "$failures feeds could not refresh"
                },
            )
        } finally {
            if (syncMutex.isLocked) syncMutex.unlock()
        }
    }

    suspend fun refreshFeed(feedId: Long): Result<Unit> {
        val feed = dao.getFeed(feedId) ?: return Result.failure(IllegalArgumentException("Feed not found."))
        return syncMutex.withLock { refreshFeedInternal(feed) }
    }

    suspend fun setRead(articleId: String, isRead: Boolean) = dao.setRead(articleId, isRead)
    suspend fun setStarred(articleId: String, isStarred: Boolean) = dao.setStarred(articleId, isStarred)
    suspend fun setArchived(articleId: String, isArchived: Boolean) = dao.setArchived(articleId, isArchived)
    suspend fun markFeedRead(feedId: Long) = dao.markFeedRead(feedId)
    suspend fun markAllRead() = dao.markAllRead()
    suspend fun deleteReadUnstarred() = dao.deleteReadUnstarred()
    suspend fun deleteFeed(feedId: Long) = dao.deleteFeed(feedId)
    fun close() = api.close()

    private suspend fun refreshFeedInternal(feed: FeedEntity): Result<Unit> = runCatching {
        when (val result = api.load(feed.url, feed.etag, feed.lastModified)) {
            FeedLoadResult.NotModified -> dao.markFeedNotModified(feed.id, System.currentTimeMillis())
            is FeedLoadResult.Loaded -> {
                dao.updateFeedAfterRefresh(
                    feedId = feed.id,
                    title = result.feed.title,
                    url = result.feedUrl,
                    siteUrl = result.feed.siteUrl,
                    description = result.feed.description,
                    fetchedAt = System.currentTimeMillis(),
                    etag = result.etag,
                    lastModified = result.lastModified,
                )
                dao.storeArticles(result.feed.toEntities(feed.id, result.feedUrl))
            }
        }
    }.onFailure { error ->
        dao.setFeedError(feed.id, friendlyMessage(error))
    }

    private fun ParsedFeed.toEntities(feedId: Long, feedUrl: String): List<ArticleEntity> =
        items.distinctBy { it.guid.ifBlank { it.link.ifBlank { it.title } } }.map { item ->
            ArticleEntity(
                id = RssParser.stableArticleId(feedUrl, item.guid, item.link, item.title),
                feedId = feedId,
                guid = item.guid,
                title = item.title.take(MAX_TITLE_LENGTH),
                link = item.link.take(MAX_URL_LENGTH),
                author = item.author.take(MAX_AUTHOR_LENGTH),
                publishedAt = item.publishedAt,
                summary = item.summary.take(MAX_SUMMARY_LENGTH),
                content = item.content.take(MAX_CONTENT_LENGTH),
                imageUrl = item.imageUrl.take(MAX_URL_LENGTH),
                contentBlocks = ContentBlocks.encode(item.blocks).let { encoded ->
                    // Cut on a record boundary: a half-written line would decode into junk.
                    if (encoded.length <= MAX_CONTENT_LENGTH) {
                        encoded
                    } else {
                        encoded.take(MAX_CONTENT_LENGTH).substringBeforeLast('\n', "")
                    }
                },
            )
        }

    companion object {
        private const val MAX_CACHED_READER_PAGES = 12
        private const val STARTER_FEEDS_KEY = "starter_feeds_added"
        private const val SHOW_IMAGES_KEY = "show_images"
        private const val AUTO_REFRESH_AGE_MS = 15 * 60 * 1_000L
        private const val MAX_TITLE_LENGTH = 600
        private const val MAX_AUTHOR_LENGTH = 300
        private const val MAX_URL_LENGTH = 4_000
        private const val MAX_SUMMARY_LENGTH = 12_000
        private const val MAX_CONTENT_LENGTH = 100_000

        private val STARTER_FEEDS = listOf(
            "NASA" to "https://www.nasa.gov/feed/",
            "BBC World" to "https://feeds.bbci.co.uk/news/world/rss.xml",
            "Hacker News" to "https://hnrss.org/frontpage",
        )

        fun friendlyMessage(error: Throwable): String {
            val message = error.message.orEmpty()
            return when {
                "timeout" in message.lowercase() -> "The request timed out."
                "unable to resolve" in message.lowercase() || "unknownhost" in message.lowercase() ->
                    "No network connection."
                message.isNotBlank() -> message.take(180)
                else -> "The feed could not be refreshed."
            }
        }
    }
}
