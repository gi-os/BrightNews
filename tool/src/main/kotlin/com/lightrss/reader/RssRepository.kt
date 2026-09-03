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

/**
 * Reader-mode result: the extracted page, the address it actually came from, and whether the
 * publisher put a bot check or sign-in wall in the way instead of the article.
 */
data class ReaderResult(val page: ReaderPage, val url: String, val gated: Boolean)

sealed interface FeedLoadResult {
    data class Loaded(
        val feed: ParsedFeed,
        val feedUrl: String,
        val etag: String?,
        val lastModified: String?,
    ) : FeedLoadResult

    data object NotModified : FeedLoadResult
}

/** A plain text or JSON document fetched conditionally. */
sealed interface TextLoadResult {
    data class Loaded(val body: String, val etag: String?, val lastModified: String?) : TextLoadResult
    data object NotModified : TextLoadResult
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
        val body = response.bodyAsText().trimXmlProlog()
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
        val feedBody = feedResponse.bodyAsText().trimXmlProlog()
        val effectiveFeedUrl = feedResponse.call.request.url.toString()
        return FeedLoadResult.Loaded(
            feed = RssParser.parse(feedBody, effectiveFeedUrl),
            feedUrl = effectiveFeedUrl,
            etag = feedResponse.headers[HttpHeaders.ETag],
            lastModified = feedResponse.headers[HttpHeaders.LastModified],
        )
    }

    /**
     * Fetches a text document — Kagi's JSON — with the same conditional headers a feed gets.
     * No feed discovery, no HTML sniffing: the caller knows exactly what it asked for.
     */
    suspend fun loadText(url: String, etag: String? = null, lastModified: String? = null): TextLoadResult {
        val response = client.get(url) {
            header(HttpHeaders.UserAgent, USER_AGENT)
            header(HttpHeaders.Accept, "application/json, text/plain;q=0.9, */*;q=0.5")
            if (!etag.isNullOrBlank()) header(HttpHeaders.IfNoneMatch, etag)
            if (!lastModified.isNullOrBlank()) header(HttpHeaders.IfModifiedSince, lastModified)
        }
        if (response.status == HttpStatusCode.NotModified) return TextLoadResult.NotModified
        ensureSuccess(response)
        return TextLoadResult.Loaded(
            body = response.bodyAsText(),
            etag = response.headers[HttpHeaders.ETag],
            lastModified = response.headers[HttpHeaders.LastModified],
        )
    }

    /**
     * Fetches an article page as HTML for reader mode, following redirects to wherever the link
     * actually lands. Sites that turn away the Light RSS user agent are retried once as a desktop
     * browser, which is what the reader is standing in for.
     */
    suspend fun loadPage(
        url: String,
        cookies: String? = null,
        userAgent: String? = null,
    ): PageDocument {
        val normalized = normalizeUrl(url)
        // A cookie earned in the sign-in view is only accepted alongside the user agent that
        // earned it, so prefer that pair when we have one.
        val firstAgent = userAgent?.takeIf { it.isNotBlank() } ?: USER_AGENT
        var response = pageRequest(normalized, firstAgent, cookies)
        if (response.status.value in BLOCKED_STATUSES && firstAgent == USER_AGENT) {
            response = pageRequest(normalized, BROWSER_USER_AGENT, cookies)
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

    private suspend fun pageRequest(
        url: String,
        userAgent: String,
        cookies: String?,
    ): HttpResponse =
        client.get(url) {
            header(HttpHeaders.UserAgent, userAgent)
            header(HttpHeaders.Accept, "text/html,application/xhtml+xml;q=0.9,*/*;q=0.5")
            header(HttpHeaders.AcceptLanguage, "en-US,en;q=0.9")
            if (!cookies.isNullOrBlank()) header(HttpHeaders.Cookie, cookies)
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

    private suspend fun request(url: String, etag: String?, lastModified: String?): HttpResponse {
        // OkHttp never puts a URL's user:pass@ on the wire, so a feed address carrying
        // credentials was silently fetched with none. Send them as the header they meant.
        val (target, authorization) = splitUserInfo(url)
        return client.get(target) {
            header(HttpHeaders.UserAgent, USER_AGENT)
            header(HttpHeaders.Accept, "application/rss+xml, application/atom+xml, application/xml, text/xml, text/html;q=0.8")
            if (authorization != null) header(HttpHeaders.Authorization, authorization)
            if (!etag.isNullOrBlank()) header(HttpHeaders.IfNoneMatch, etag)
            if (!lastModified.isNullOrBlank()) header(HttpHeaders.IfModifiedSince, lastModified)
        }
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

        /**
         * A UTF-8 BOM or stray whitespace ahead of the XML declaration makes SAX refuse the
         * whole document ("content is not allowed in prolog"), and real feeds ship both.
         */
        internal fun String.trimXmlProlog(): String =
            trimStart().removePrefix("\uFEFF").trimStart()

        /**
         * Splits `user:pass@` userinfo out of a URL and returns the bare URL alongside the
         * Basic Authorization header value it stood for, or null when there was none.
         */
        internal fun splitUserInfo(url: String): Pair<String, String?> {
            val uri = runCatching { URI(url) }.getOrNull() ?: return url to null
            val userInfo = uri.userInfo ?: return url to null
            val stripped = runCatching {
                URI(uri.scheme, null, uri.host, uri.port, uri.path, uri.query, uri.fragment).toString()
            }.getOrDefault(url)
            val encoded = java.util.Base64.getEncoder()
                .encodeToString(userInfo.toByteArray(Charsets.UTF_8))
            return stripped to "Basic $encoded"
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

    /** The Gmail side. See [NewsletterSync] for why a label is modelled as a feed. */
    val newsletters = NewsletterSync(dao)

    /**
     * The article list. [source] picks a section ([Source.RSS], [Source.GMAIL]) or null for
     * both; [favoritesOnly] narrows to feeds the reader starred, [unreadOnly] to articles they
     * have not opened yet. All three are independent.
     */
    fun observeInbox(
        unreadOnly: Boolean,
        favoritesOnly: Boolean = false,
        source: String? = null,
    ): Flow<List<ArticleRow>> = dao.observeInbox(unreadOnly, favoritesOnly, source)

    /** Unread in one section, for the count beside it on the home screen. */
    fun observeUnreadCount(source: String): Flow<Int> =
        dao.observeUnreadCount(source).distinctUntilChanged()

    fun observeFavoriteFeedCount(): Flow<Int> = dao.observeFavoriteFeedCount().distinctUntilChanged()

    suspend fun setFeedFavorite(feedId: Long, isFavorite: Boolean) =
        dao.setFeedFavorite(feedId, isFavorite)

    /** Whether the home list is narrowed to favourite feeds. Defaults to off. */
    val homeFavoritesOnly: Flow<Boolean> = dao.observeMetadata(HOME_FAVORITES_KEY)
        .map { it == "1" }
        .distinctUntilChanged()

    /** Whether the list hides what has been read. Defaults to on. */
    val homeUnreadOnly: Flow<Boolean> = dao.observeMetadata(HOME_UNREAD_KEY)
        .map { it != "0" }
        .distinctUntilChanged()

    suspend fun setHomeUnreadOnly(enabled: Boolean) {
        dao.putMetadata(AppMetadataEntity(HOME_UNREAD_KEY, if (enabled) "1" else "0"))
    }

    suspend fun setHomeFavoritesOnly(enabled: Boolean) {
        dao.putMetadata(AppMetadataEntity(HOME_FAVORITES_KEY, if (enabled) "1" else "0"))
    }

    fun observeStarred(): Flow<List<ArticleRow>> = dao.observeStarred()
    fun observeArchived(): Flow<List<ArticleRow>> = dao.observeArchived()
    fun observeFeeds(source: String? = null): Flow<List<FeedRow>> = dao.observeFeeds(source)
    fun observeKagiFeeds(): Flow<List<FeedRow>> = dao.observeKagiFeeds()

    fun observeKagiEdition(): Flow<List<ArticleRow>> = dao.observeKagiEdition()
    fun observeTimeline(unreadOnly: Boolean, favoritesOnly: Boolean): Flow<List<ArticleRow>> =
        dao.observeTimeline(unreadOnly, favoritesOnly)
    fun observeTimelineUnread(): Flow<Int> = dao.observeTimelineUnread().distinctUntilChanged()

    /** "1 of 12": a Kagi story's place in its category, or null for anything else. */
    suspend fun kagiPosition(article: ArticleEntity): Pair<Int, Int>? {
        if (!article.guid.startsWith("kagi:")) return null
        val above = dao.countRankedAbove(article.feedId, article.publishedAt)
        val total = dao.countInFeed(article.feedId)
        return (above + 1) to total
    }

    /** Which home tab was open last; the briefing until the reader says otherwise. */
    val homeSection: Flow<String> = dao.observeMetadata(HOME_SECTION_KEY)
        .map { it ?: HomeSection.BRIEFING }
        .distinctUntilChanged()

    suspend fun setHomeSection(section: String) {
        dao.putMetadata(AppMetadataEntity(HOME_SECTION_KEY, section))
    }

    /** The article after [article] in its feed, in list order; null at the end. */
    suspend fun nextInFeed(article: ArticleEntity): ArticleEntity? =
        dao.nextInFeed(article.feedId, article.id, article.publishedAt, article.insertedAt)

    /** The article before [article] in its feed; null at the start. */
    suspend fun previousInFeed(article: ArticleEntity): ArticleEntity? =
        dao.previousInFeed(article.feedId, article.id, article.publishedAt, article.insertedAt)
    fun observeFeed(feedId: Long): Flow<FeedEntity?> = dao.observeFeed(feedId)
    fun observeFeedArticles(feedId: Long): Flow<List<ArticleRow>> = dao.observeFeedArticles(feedId)
    fun observeArticle(articleId: String): Flow<ArticleRow?> = dao.observeArticle(articleId)
    fun search(query: String): Flow<List<ArticleRow>> = dao.observeSearch(query.trim())

    /** Whether feed images should be downloaded and shown. Defaults to on. */
    val imagesEnabled: Flow<Boolean> = dao.observeMetadata(SHOW_IMAGES_KEY)
        .map { it != "0" }
        .distinctUntilChanged()

    suspend fun setImagesEnabled(enabled: Boolean) {
        val previous = dao.getMetadata(SHOW_IMAGES_KEY) != "0"
        dao.putMetadata(AppMetadataEntity(SHOW_IMAGES_KEY, if (enabled) "1" else "0"))
        if (!enabled) {
            images?.clear()
            return
        }
        // Switching images back on has to throw the cached newsletter bodies away. A
        // newsletter's own art is inlined once, when the message is stored, because a WebView
        // cannot attach an OAuth header to fetch a MIME part later — so a body cached with
        // images off has no art in it and never will. The next sync refetches them.
        if (!previous) newsletters.clearBodies()
    }

    fun clearImageCache() = images?.clear()

    /**
     * Whether to hold the phone in colour while an image is on screen. Defaults to on, and does
     * nothing at all until the adb grant in [ColorMode] has been given.
     */
    val colourEnabled: Flow<Boolean> = dao.observeMetadata(COLOUR_KEY)
        .map { it != "0" }
        .distinctUntilChanged()

    suspend fun setColourEnabled(enabled: Boolean) {
        dao.putMetadata(AppMetadataEntity(COLOUR_KEY, if (enabled) "1" else "0"))
    }

    private val readerPages = mutableMapOf<String, ReaderResult>()
    private val readerLock = Mutex()

    /**
     * Reader-mode version of an article's linked page. Cached per session so paging back and
     * forth does not re-fetch, and so the page stays readable once it has been downloaded.
     */
    /** Keeps the cookies and user agent from the sign-in view, per host. */
    suspend fun setSiteAccess(url: String, cookies: String?, userAgent: String?) {
        val host = hostOf(url) ?: return
        if (!cookies.isNullOrBlank()) dao.putMetadata(AppMetadataEntity("$COOKIE_KEY$host", cookies))
        if (!userAgent.isNullOrBlank()) dao.putMetadata(AppMetadataEntity("$AGENT_KEY$host", userAgent))
        readerLock.withLock { readerPages.clear() }
    }

    /** True when this article's host has a stored sign-in. */
    suspend fun hasSiteAccess(url: String): Boolean {
        val host = hostOf(url) ?: return false
        return !dao.getMetadata("$COOKIE_KEY$host").isNullOrBlank()
    }

    private suspend fun fetchPage(url: String): PageDocument {
        val host = hostOf(url)
        val cookies = host?.let { dao.getMetadata("$COOKIE_KEY$it") }
        val agent = host?.let { dao.getMetadata("$AGENT_KEY$it") }
        return api.loadPage(url, cookies, agent)
    }

    private fun hostOf(url: String): String? =
        runCatching { URI(url).host?.lowercase()?.removePrefix("www.") }.getOrNull()

    suspend fun readerPage(articleId: String, url: String, refresh: Boolean = false): ReaderResult {
        if (url.isBlank()) throw IllegalArgumentException("This article has no link to open.")
        if (!refresh) {
            readerLock.withLock { readerPages[articleId] }?.let { return it }
        }

        var document = fetchPage(url)

        // Feeds link through redirectors and tracking variants. Take the page's word for where
        // the real article lives before deciding there is nothing to read.
        val hop = ReaderExtractor.metaRefreshUrl(document.html, document.url)
            ?: ReaderExtractor.canonicalUrl(document.html, document.url)
        if (hop != null) {
            runCatching { fetchPage(hop) }.getOrNull()?.let { document = it }
        }

        var page = ReaderExtractor.extract(document.html, document.url)

        // Some publishers keep a lighter AMP copy that a text reader can actually use.
        if (!with(ReaderExtractor) { page.hasContent() }) {
            ReaderExtractor.ampUrl(document.html, document.url)?.let { amp ->
                runCatching { fetchPage(amp) }.getOrNull()?.let { ampDocument ->
                    val ampPage = ReaderExtractor.extract(ampDocument.html, ampDocument.url)
                    if (with(ReaderExtractor) { ampPage.hasContent() }) page = ampPage
                }
            }
        }

        val result = ReaderResult(
            page = page,
            url = document.url,
            gated = ReaderExtractor.isGate(document.html, page),
        )
        readerLock.withLock {
            if (readerPages.size >= MAX_CACHED_READER_PAGES) readerPages.clear()
            readerPages[articleId] = result
        }
        return result
    }

    /* ------------------------------------------------------------------ full text */

    /** Whether the reader fetches the whole article behind a feed's summary. On by default. */
    val fullTextEnabled: Flow<Boolean> = dao.observeMetadata(FULL_TEXT_KEY)
        .map { it != "0" }
        .distinctUntilChanged()

    suspend fun setFullTextEnabled(enabled: Boolean) {
        dao.putMetadata(AppMetadataEntity(FULL_TEXT_KEY, if (enabled) "1" else "0"))
    }

    /**
     * Fetches the whole article behind [article]'s link and stores it on the row, so the reader
     * shows the story where the feed's paragraph was — now, and offline later.
     *
     * Returns true when something readable was stored. A paywall, a bot check or a page with no
     * body copy stores [FULL_TEXT_UNAVAILABLE] instead, so the next refresh does not try the same
     * dead page again; the reader's OPEN row still tries on demand. Only RSS articles qualify —
     * a newsletter is already whole, and a Kagi story links to forty sources, not one page.
     */
    suspend fun fetchFullText(article: ArticleEntity, force: Boolean = false): Boolean {
        if (article.link.isBlank()) return false
        if (!force && article.readerBlocks.isNotEmpty()) return article.readerBlocks != FULL_TEXT_UNAVAILABLE
        if (NewsletterSync.isNewsletter(article.id)) return false
        val result = runCatching { readerPage(article.id, article.link, refresh = force) }.getOrNull()
        val readable = result != null && with(ReaderExtractor) { result.page.hasContent() } && !result.gated
        if (!readable) {
            if (article.readerBlocks.isEmpty()) dao.setReaderBlocks(article.id, FULL_TEXT_UNAVAILABLE)
            return false
        }
        val encoded = ContentBlocks.encode(result!!.page.blocks).let { encoded ->
            if (encoded.length <= MAX_CONTENT_LENGTH) encoded else encoded.take(MAX_CONTENT_LENGTH).substringBeforeLast('\n', "")
        }
        if (encoded.isBlank()) return false
        dao.setReaderBlocks(article.id, encoded)
        return true
    }

    /**
     * Full text for the newest unread articles, ahead of anyone opening them. Bounded, and run
     * after the feeds themselves so a slow publisher never holds up the sync count.
     */
    private suspend fun prefetchFullText() {
        if (dao.getMetadata(FULL_TEXT_KEY) == "0") return
        val pending = dao.articlesAwaitingFullText(FULL_TEXT_PREFETCH_LIMIT)
        for (article in pending) {
            runCatching { fetchFullText(article) }
        }
    }

    /* ------------------------------------------------------------------ Kagi */

    /**
     * Kagi's category index, fetched at most once a day. Categories are added and removed rarely,
     * and the picker is the only reader of this, so a day-old list is as good as a fresh one.
     */
    suspend fun kagiCategories(force: Boolean = false): List<Kagi.Category> {
        val cachedAt = dao.getMetadata(KAGI_INDEX_AT_KEY)?.toLongOrNull() ?: 0L
        val cached = dao.getMetadata(KAGI_INDEX_KEY)
        if (!force && cached != null && System.currentTimeMillis() - cachedAt < KAGI_INDEX_AGE_MS) {
            runCatching { Kagi.parseIndex(cached) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        val loaded = try {
            api.loadText(Kagi.INDEX_URL) as TextLoadResult.Loaded
        } catch (error: Throwable) {
            // Offline: the stale copy beats an empty picker.
            cached?.let { runCatching { Kagi.parseIndex(it) }.getOrNull() }?.takeIf { it.isNotEmpty() }?.let { return it }
            throw error
        }
        val categories = Kagi.parseIndex(loaded.body)
        if (categories.isEmpty()) throw IllegalStateException("Kagi News returned no categories.")
        dao.putMetadata(AppMetadataEntity(KAGI_INDEX_KEY, loaded.body))
        dao.putMetadata(AppMetadataEntity(KAGI_INDEX_AT_KEY, System.currentTimeMillis().toString()))
        return categories
    }

    /** The Kagi categories already followed, by category file url. */
    suspend fun followedKagiUrls(): Set<String> = dao.getKagiFeeds().map { it.url }.toSet()

    /** Follows a Kagi category and fetches today's edition. Returns the new feed's id. */
    suspend fun addKagiCategory(category: Kagi.Category): Long {
        dao.getFeedByUrl(category.url)?.let { return it.id }
        val feedId = dao.insertFeed(
            FeedEntity(
                title = category.name,
                url = category.url,
                siteUrl = Kagi.BASE_URL,
                description = "Kagi News",
                sourceType = Source.KAGI,
            ),
        )
        val feed = dao.getFeed(feedId) ?: return feedId
        syncMutex.withLock { refreshFeedInternal(feed) }.getOrThrow()
        return feedId
    }

    /** The Kagi category after [feedId] in reading order, wrapping round; null when it is alone. */
    suspend fun nextKagiFeed(feedId: Long): FeedEntity? {
        val feeds = dao.getKagiFeeds()
        if (feeds.size < 2) return null
        val index = feeds.indexOfFirst { it.id == feedId }
        if (index < 0) return null
        return feeds[(index + 1) % feeds.size]
    }

    private suspend fun refreshKagi(feed: FeedEntity) {
        when (val result = api.loadText(feed.url, feed.etag, feed.lastModified)) {
            TextLoadResult.NotModified -> dao.markFeedNotModified(feed.id, System.currentTimeMillis())
            is TextLoadResult.Loaded -> {
                val edition = Kagi.parseEdition(result.body)
                dao.updateFeedAfterRefresh(
                    feedId = feed.id,
                    title = feed.title,
                    siteUrl = Kagi.BASE_URL,
                    description = "Kagi News",
                    fetchedAt = System.currentTimeMillis(),
                    etag = result.etag,
                    lastModified = result.lastModified,
                )
                val since = edition.publishedAt - KAGI_DUPLICATE_WINDOW_MS
                val upserts = edition.clusters
                    .distinctBy { Kagi.storyGuid(it.title) }
                    .filter { cluster ->
                        dao.kagiStoryHeldElsewhere(feed.id, Kagi.storyGuid(cluster.title), since) == 0
                    }
                    .map { cluster -> kagiUpsert(feed.id, edition, cluster) }
                dao.storeArticles(upserts)
                dao.trimKagi(feed.id, before = System.currentTimeMillis() - KAGI_KEEP_MS)
            }
        }
    }

    private fun kagiUpsert(feedId: Long, edition: Kagi.Edition, cluster: Kagi.Cluster): ArticleUpsert {
        val guid = Kagi.storyGuid(cluster.title)
        val topStory = cluster.sources.firstOrNull()?.link.orEmpty()
        return ArticleUpsert(
            article = ArticleEntity(
                id = RssParser.stableArticleId(feedId, guid, topStory, cluster.title),
                feedId = feedId,
                guid = guid,
                title = cluster.title.take(MAX_TITLE_LENGTH),
                link = topStory.take(MAX_URL_LENGTH),
                author = listOf(cluster.topic, cluster.location).filter { it.isNotBlank() }.joinToString(" · ").take(MAX_AUTHOR_LENGTH),
                publishedAt = Kagi.storyPublishedAt(edition, cluster),
                summary = Kagi.stripCitations(cluster.summary).take(MAX_SUMMARY_LENGTH),
                imageUrl = cluster.imageUrl.take(MAX_URL_LENGTH),
                sourceCount = maxOf(cluster.sourceCount, cluster.sources.size),
                contentBlocks = ContentBlocks.encode(Kagi.blocks(cluster)).let { encoded ->
                    if (encoded.length <= MAX_CONTENT_LENGTH) encoded else encoded.take(MAX_CONTENT_LENGTH).substringBeforeLast('\n', "")
                },
            ),
            hasDate = true,
        )
    }

    suspend fun initialize() {
        // One-time data migration, before any refresh can write rows under the old scheme:
        // ids used to hash the fetch's effective URL, and this re-keys every RSS article onto
        // the feed's database id. Guarded by its own flag, so it is a no-op ever after.
        dao.rekeyRssArticleIdsOnce()
        // Published before anything reads it, so the newsletters section can render its
        // signed-in state without suspending on the first frame.
        newsletters.auth.refreshState()
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
        // Articles are built after the insert, because their ids hash the feed's database id
        // and that id does not exist until the feed row does.
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
            articles = { feedId -> loaded.feed.toEntities(feedId) },
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
            runCatching { prefetchFullText() }
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

    /**
     * Reading a newsletter has to reach Gmail; reading an article only has to reach this
     * table. [NewsletterSync.markRead] flips the row first and pushes second, so the two
     * behave identically from the reader's side.
     */
    suspend fun setRead(articleId: String, isRead: Boolean) {
        if (!NewsletterSync.isNewsletter(articleId)) {
            dao.setRead(articleId, isRead)
            return
        }
        if (isRead) {
            newsletters.markRead(articleId)
        } else {
            // Gmail is never told to re-mark something unread — the app only ever clears
            // UNREAD. Clearing pendingRead alongside is what stops the next sync from pushing
            // a read the reader has just taken back.
            dao.setReadPending(articleId, isRead = false, pending = false)
        }
    }

    suspend fun setStarred(articleId: String, isStarred: Boolean) = dao.setStarred(articleId, isStarred)
    suspend fun setArchived(articleId: String, isArchived: Boolean) = dao.setArchived(articleId, isArchived)

    /** Put the whole archive back, for when the hiding was the mistake. */
    suspend fun unarchiveAll() = dao.unarchiveAll()

    suspend fun markFeedRead(feedId: Long) {
        dao.markFeedRead(feedId)
        dao.queueNewsletterReads(feedId)
    }

    suspend fun markAllRead() {
        dao.markAllRead()
        dao.queueNewsletterReads(null)
    }

    suspend fun deleteReadUnstarred() = dao.deleteReadUnstarred()
    suspend fun deleteFeed(feedId: Long) = dao.deleteFeed(feedId)

    fun close() {
        api.close()
        newsletters.close()
    }

    private suspend fun refreshFeedInternal(feed: FeedEntity): Result<Unit> = runCatching {
        if (feed.sourceType == Source.GMAIL) {
            newsletters.sync(feed, loadImages = dao.getMetadata(SHOW_IMAGES_KEY) != "0")
            dao.markFeedNotModified(feed.id, System.currentTimeMillis())
            return@runCatching
        }
        if (feed.sourceType == Source.KAGI) {
            refreshKagi(feed)
            return@runCatching
        }
        when (val result = api.load(feed.url, feed.etag, feed.lastModified)) {
            FeedLoadResult.NotModified -> dao.markFeedNotModified(feed.id, System.currentTimeMillis())
            is FeedLoadResult.Loaded -> {
                // The fetch's effective URL is deliberately not stored: the address the reader
                // subscribed with is the feed's identity, and rewriting it to wherever this
                // fetch happened to land let a rotating mirror rename the feed every refresh.
                dao.updateFeedAfterRefresh(
                    feedId = feed.id,
                    title = result.feed.title,
                    siteUrl = result.feed.siteUrl,
                    description = result.feed.description,
                    fetchedAt = System.currentTimeMillis(),
                    etag = result.etag,
                    lastModified = result.lastModified,
                )
                dao.storeArticles(result.feed.toEntities(feed.id))
            }
        }
    }.onFailure { error ->
        dao.setFeedError(feed.id, friendlyMessage(error))
    }

    private fun ParsedFeed.toEntities(feedId: Long): List<ArticleUpsert> =
        items.distinctBy { it.guid.ifBlank { it.link.ifBlank { it.title } } }.map { item ->
            ArticleUpsert(
                article = ArticleEntity(
                    id = RssParser.stableArticleId(feedId, item.guid, item.link, item.title),
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
                ),
                hasDate = item.hasDate,
            )
        }

    companion object {
        private const val MAX_CACHED_READER_PAGES = 12
        private const val COOKIE_KEY = "site_cookies:"
        private const val AGENT_KEY = "site_agent:"
        private const val STARTER_FEEDS_KEY = "starter_feeds_added"
        private const val SHOW_IMAGES_KEY = "show_images"
        private const val FULL_TEXT_KEY = "full_text"
        private const val HOME_SECTION_KEY = "home_section"
        private const val KAGI_INDEX_KEY = "kagi_index"
        private const val KAGI_INDEX_AT_KEY = "kagi_index_at"
        private const val KAGI_INDEX_AGE_MS = 24 * 60 * 60 * 1_000L
        /** A story seen in another category this recently is the same story, not a new one. */
        private const val KAGI_DUPLICATE_WINDOW_MS = 36 * 60 * 60 * 1_000L
        /** A week of editions per category; saved and archived stories are exempt from the trim. */
        private const val KAGI_KEEP_MS = 7 * 24 * 60 * 60 * 1_000L
        /** Articles fetched in full per refresh, newest unread first. */
        private const val FULL_TEXT_PREFETCH_LIMIT = 12

        /**
         * Stored in [ArticleEntity.readerBlocks] when the page could not be read — decodes to
         * no blocks, so the reader falls back to the feed's own text.
         */
        const val FULL_TEXT_UNAVAILABLE = "-"
        private const val COLOUR_KEY = "lift_greyscale"
        private const val HOME_FAVORITES_KEY = "home_favorites_only"
        private const val HOME_UNREAD_KEY = "home_unread_only"
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
