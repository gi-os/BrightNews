package com.lightrss.reader

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URI

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

    fun close() = client.close()

    private suspend fun request(url: String, etag: String?, lastModified: String?): HttpResponse =
        client.get(url) {
            header(HttpHeaders.UserAgent, "LightRSS/1.0 (Light Phone III)")
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
) {
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

    suspend fun initialize() {
        if (dao.getMetadata(STARTER_FEEDS_KEY) != null) return
        STARTER_FEEDS.forEach { starter ->
            runCatching {
                dao.insertFeed(FeedEntity(title = starter.first, url = starter.second))
            }
        }
        dao.putMetadata(AppMetadataEntity(STARTER_FEEDS_KEY, "1"))
    }

    suspend fun addFeed(rawUrl: String): Long {
        val normalized = RssApi.normalizeUrl(rawUrl)
        dao.getFeedByUrl(normalized)?.let { throw IllegalArgumentException("You already follow this feed.") }
        val result = api.load(normalized)
        val loaded = result as? FeedLoadResult.Loaded
            ?: throw IllegalStateException("The feed was not available.")
        dao.getFeedByUrl(loaded.feedUrl)?.let { throw IllegalArgumentException("You already follow this feed.") }
        return dao.insertFeedWithArticles(
            feed = FeedEntity(
                title = loaded.feed.title,
                url = loaded.feedUrl,
                siteUrl = loaded.feed.siteUrl,
                description = loaded.feed.description,
                lastFetchedAt = System.currentTimeMillis(),
                etag = loaded.etag,
                lastModified = loaded.lastModified,
            ),
            articles = loaded.feed.toEntities(feedId = 0, feedUrl = loaded.feedUrl),
        )
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
            )
        }

    companion object {
        private const val STARTER_FEEDS_KEY = "starter_feeds_added"
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
