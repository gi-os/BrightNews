package com.lightrss.reader

import com.lightrss.reader.gmail.AuthStore
import com.lightrss.reader.gmail.GmailAuth
import com.lightrss.reader.gmail.GmailClient
import com.lightrss.reader.gmail.GmailHttpError
import com.lightrss.reader.gmail.GmailLabel
import com.lightrss.reader.gmail.InlineImage
import com.lightrss.reader.gmail.RawMessage
import com.lightrss.reader.gmail.ReauthRequired
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Why a newsletter sync could not run, in the words the status line uses. */
class NewsletterError(message: String) : Exception(message)

/**
 * The Gmail half of the reader.
 *
 * A subscribed label is a [FeedEntity] with [FeedEntity.sourceType] `GMAIL`, and each of its
 * messages is an [ArticleEntity]. Nothing downstream of this file knows the difference — the
 * home list, search, saved items, read state and the article cache are the RSS ones, unchanged.
 * That is the point of the merge: the second source cost a sync and a renderer, not a second app.
 */
class NewsletterSync(private val dao: RssDao) {

    /** Auth credentials live in `app_metadata`, next to the reader's other stored secrets. */
    val auth = GmailAuth(
        object : AuthStore {
            override suspend fun read(key: String): String? =
                dao.getMetadata(key)?.takeIf { it.isNotEmpty() }

            override suspend fun write(key: String, value: String?) {
                if (value == null) dao.deleteMetadata(key) else dao.putMetadata(AppMetadataEntity(key, value))
            }
        },
    )

    private val gmail = GmailClient(auth)

    /**
     * Fetches that keep failing — a message too large to buffer, most likely. Without this
     * they are retried on every sync forever. In memory on purpose: a restart is a reasonable
     * moment to try again.
     */
    private val failedFetches = mutableMapOf<String, Int>()

    /* ------------------------------------------------------------------- settings */

    val renderMode: Flow<RenderMode> = dao.observeMetadata(RENDER_MODE_KEY)
        .map { if (it == "paper") RenderMode.PAPER else RenderMode.DARK }
        .distinctUntilChanged()

    suspend fun setRenderMode(mode: RenderMode) {
        dao.putMetadata(
            AppMetadataEntity(RENDER_MODE_KEY, if (mode == RenderMode.PAPER) "paper" else "dark"),
        )
    }

    /** Strip sponsor blocks at render time, so switching it off shows them again immediately. */
    val blockAds: Flow<Boolean> = dao.observeMetadata(BLOCK_ADS_KEY)
        .map { it != "0" }
        .distinctUntilChanged()

    suspend fun setBlockAds(enabled: Boolean) {
        dao.putMetadata(AppMetadataEntity(BLOCK_ADS_KEY, if (enabled) "1" else "0"))
    }

    /* ------------------------------------------------------------ subscribing */

    suspend fun listLabels(): List<GmailLabel> = gmail.listLabels()

    /** Subscribe to a Gmail label. Its messages arrive on the next refresh. */
    suspend fun addLabel(label: GmailLabel): Long {
        val url = labelUrl(label.name)
        dao.getFeedByUrl(url)?.let { throw IllegalArgumentException("You already follow this label.") }
        return dao.insertFeed(
            FeedEntity(
                title = label.name.substringAfterLast('/'),
                url = url,
                description = "Gmail label",
                sourceType = Source.GMAIL,
                gmailLabel = label.name,
                gmailLabelId = label.id.takeIf { it.isNotEmpty() },
            ),
        )
    }

    /* ------------------------------------------------------------------- reading */

    /**
     * Mark a newsletter read, locally first.
     *
     * The flag flips before the network is consulted so the reader stays instant, and the row
     * keeps [ArticleEntity.pendingRead] until Gmail accepts it. If the phone is offline the
     * next sync pushes it — the only way an offline read can be honest.
     */
    suspend fun markRead(articleId: String) {
        val article = dao.getArticle(articleId) ?: return
        if (article.isRead && !article.pendingRead) return
        dao.setReadPending(articleId, isRead = true, pending = true)
        if (articleId in push(listOf(articleId))) dao.settleReads(listOf(articleId))
    }

    /**
     * Clear UNREAD on Gmail; returns the ids it accepted.
     *
     * batchModify is all-or-nothing, so one message deleted in Gmail fails the whole call —
     * and a batch that always fails means pendingRead never clears for anything, which in turn
     * stops the cache from ever being trimmed. So a failed batch is retried one id at a time,
     * and a 400/404 counts as settled: the message is gone, there is nothing left to mark.
     */
    private suspend fun push(articleIds: List<String>): Set<String> {
        if (articleIds.isEmpty()) return emptySet()
        val messageIds = articleIds.map(::messageIdOf)
        if (tryNet { gmail.markRead(messageIds); true } == true) return articleIds.toSet()

        val settled = mutableSetOf<String>()
        for (articleId in articleIds) {
            val accepted = try {
                gmail.markRead(listOf(messageIdOf(articleId)))
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The message is gone from Gmail: the read has nowhere left to go, so treat it
                // as settled or the flag never clears and the cache never trims.
                e is GmailHttpError && (e.code == 400 || e.code == 404)
            }
            if (accepted) settled += articleId
        }
        return settled
    }

    /* ---------------------------------------------------------------------- sync */

    /**
     * One label's messages, reconciled with what is already cached.
     *
     * Throws [NewsletterError] with a line the status bar can show; the caller records it on
     * the feed exactly as it records an RSS fetch failure.
     */
    suspend fun sync(feed: FeedEntity, loadImages: Boolean) {
        if (!auth.state.value.signedIn) {
            auth.refreshState()
            if (!auth.state.value.signedIn) throw NewsletterError("Sign in to Gmail in Settings.")
        }
        try {
            val justSettled = pushPending(feed.id)

            var labelId = labelId(feed) ?: throw NewsletterError("Label “${feed.gmailLabel}” is not in this mailbox.")
            val page = try {
                gmail.listIds(labelId, unreadOnly = false, max = WINDOW)
            } catch (e: GmailHttpError) {
                // A cached label id goes stale if the label is deleted and remade, or if a
                // different account signs in. Gmail answers 400/404 — re-resolve once, rather
                // than failing every sync from here to the end of time.
                if (e.code != 400 && e.code != 404) throw e
                dao.setGmailLabelId(feed.id, null)
                labelId = labelId(feed.copy(gmailLabelId = null))
                    ?: throw NewsletterError("Label “${feed.gmailLabel}” is not in this mailbox.")
                gmail.listIds(labelId, unreadOnly = false, max = WINDOW)
            }

            val inWindow = page.ids.map(::articleIdOf).toSet()
            val known = dao.articleIdsIn(feed.id).toSet()
            val withBody = dao.articleIdsWithBody(feed.id).toSet()

            // A row whose body went missing counts as missing too. Without that, the `known`
            // check would skip it forever and the reader would be stuck on its snippet.
            val missing = page.ids.filter { messageId ->
                val articleId = articleIdOf(messageId)
                (articleId !in known || articleId !in withBody) &&
                    (failedFetches[articleId] ?: 0) < MAX_FETCH_ATTEMPTS
            }
            for (messageId in missing.take(FETCH_PER_SYNC)) {
                val articleId = articleIdOf(messageId)
                val message = tryNet { gmail.fetch(messageId) }
                if (message == null) {
                    failedFetches[articleId] = (failedFetches[articleId] ?: 0) + 1
                    continue
                }
                failedFetches.remove(articleId)
                store(feed, message, loadImages)
            }

            // Read-state reconciliation is a second, cheap list call — but it is only valid
            // for the ids this run actually saw. Applied to the whole table it would mark
            // everything past the page boundary read, permanently.
            val unreadPage = gmail.listIds(labelId, unreadOnly = true, max = WINDOW)
            val unread = unreadPage.ids.map(::articleIdOf).toSet()
            // Minus what this run just pushed: messages.list is index-backed and can still
            // report an id as UNREAD seconds after Gmail accepted the change, and those rows
            // no longer carry pendingRead to protect them.
            chunked((unread intersect inWindow) - justSettled) { dao.markUnreadIn(it) }
            // And only flip rows to read if that list was exhaustive. A truncated page says
            // nothing about the messages below its last id, so reading silence as "already
            // read" would quietly hide issues that are genuinely unread.
            if (unreadPage.complete) chunked(inWindow - unread) { dao.markReadIn(it) }

            // Prune only when the whole label came back in one page; otherwise messages past
            // the page boundary look deleted and vanish on every sync. Starred and unpushed
            // rows survive — dropping either loses something the reader did.
            if (page.complete) chunked(known - inWindow) { dao.deleteStaleNewsletters(it) }
            dao.trimNewsletters(feed.id, KEEP)
        } catch (e: ReauthRequired) {
            throw NewsletterError("Gmail sign-in expired. Sign in again in Settings.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: NewsletterError) {
            throw e
        } catch (e: Exception) {
            throw NewsletterError(RssRepository.friendlyMessage(e))
        }
    }

    /** Returns the ids Gmail accepted, so the caller can distrust its own unread list. */
    private suspend fun pushPending(feedId: Long): Set<String> {
        val pending = dao.pendingReadIds(feedId)
        if (pending.isEmpty()) return emptySet()
        val settled = push(pending)
        chunked(settled) { dao.settleReads(it) }
        return settled
    }

    /** Cached on the feed row, because resolving the name costs a round trip every sync. */
    private suspend fun labelId(feed: FeedEntity): String? {
        feed.gmailLabelId?.takeIf { it.isNotBlank() }?.let { return it }
        val name = feed.gmailLabel ?: return null
        val resolved = gmail.findLabelId(name) ?: return null
        dao.setGmailLabelId(feed.id, resolved)
        return resolved
    }

    private suspend fun store(feed: FeedEntity, message: RawMessage, loadImages: Boolean) {
        val html = message.html
        val body = if (html != null) {
            NewsletterHtml.inlineCids(html, if (loadImages) fetchInline(message) else emptyMap())
        } else {
            ""
        }
        dao.storeNewsletter(
            article = ArticleEntity(
                id = articleIdOf(message.id),
                feedId = feed.id,
                guid = message.id,
                title = message.subject.take(MAX_TITLE),
                // No web address exists for an email, and a blank link is what tells the
                // reader not to offer "open the full page".
                link = "",
                author = message.fromName.ifBlank { message.fromEmail }.take(MAX_AUTHOR),
                publishedAt = message.dateMs,
                summary = message.snippet.take(MAX_SUMMARY),
                // The plain-text alternative, kept for search and for the case where this
                // device turns out to have no WebView. The HTML lives in its own table.
                content = (message.text ?: message.snippet).take(MAX_CONTENT),
                isRead = !message.unread,
            ),
            html = body,
        )
    }

    /** Inline images, smallest first, until the budget runs out. Logos fit; hero art doesn't. */
    private suspend fun fetchInline(message: RawMessage): Map<String, InlineImage> {
        var budget = INLINE_BUDGET_BYTES
        return buildMap {
            for (part in message.inlineParts.sortedBy { it.size }) {
                if (part.size > budget) break
                val bytes = tryNet { gmail.attachment(message.id, part.attachmentId) } ?: continue
                budget -= bytes.size
                put(part.contentId, InlineImage(part.mimeType, bytes))
            }
        }
    }

    suspend fun body(articleId: String): String? = dao.getBody(articleId)

    /** Throws cached bodies away so the next sync refetches them with art. */
    suspend fun clearBodies() = dao.clearBodies()

    /**
     * Disconnect the mailbox.
     *
     * Takes the cached issues with it. They are somebody's mail and the account that authorised
     * them is gone, so leaving them on the phone is not a caching decision. Followed labels stay
     * as feed rows and refill on the next sign-in — it is the account that was removed, not the
     * choice of what to read.
     *
     * [forgetClient] additionally drops the OAuth client id, which is what makes a wrong one
     * recoverable; see [GmailAuth.forget].
     */
    suspend fun signOut(forgetClient: Boolean = false) {
        if (forgetClient) auth.forget() else auth.signOut()
        dao.deleteAllNewsletters()
        dao.resetNewsletterFeeds()
        failedFetches.clear()
    }

    fun close() {
        gmail.close()
        auth.close()
    }

    /**
     * A network call whose failure is not fatal. Cancellation is rethrown rather than counted
     * as a failure: a plain runCatching swallows it, and then a sync cancelled on the way out
     * of the app records a "failure" for every message it had left to fetch — enough to
     * blacklist them for the life of the process.
     */
    private suspend fun <T> tryNet(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /**
     * SQLite caps the number of bound variables, so every `IN (:ids)` query is chunked. The
     * cap is generous, but "unbounded list into one query" is how a sync starts failing with
     * `too many SQL variables` and never recovers.
     */
    private suspend fun chunked(ids: Set<String>, action: suspend (List<String>) -> Unit) {
        if (ids.isEmpty()) return
        ids.chunked(SQL_CHUNK).forEach { action(it) }
    }

    companion object {
        /** Article ids are namespaced so a Gmail message can never collide with a feed item. */
        private const val ID_PREFIX = "gmail:"

        fun articleIdOf(messageId: String) = "$ID_PREFIX$messageId"
        fun messageIdOf(articleId: String) = articleId.removePrefix(ID_PREFIX)
        fun labelUrl(labelName: String) = "gmail://label/$labelName"

        /**
         * Whether an article came from a mailbox.
         *
         * Read from the id rather than by joining back to the feed, because every caller
         * already has the id and none of them wants a database round trip to decide how to
         * mark something read.
         */
        fun isNewsletter(articleId: String) = articleId.startsWith(ID_PREFIX)

        private const val RENDER_MODE_KEY = "newsletter_render_mode"
        private const val BLOCK_ADS_KEY = "newsletter_block_ads"

        /** How many of the label's newest messages one sync looks at. */
        private const val WINDOW = 100

        /**
         * How many issues the cache keeps. Deliberately larger than [WINDOW]: at exactly
         * WINDOW, a single row outside the window — a message deleted in Gmail, or one holding
         * an unpushed read — costs a cache slot, so the oldest in-window message is fetched
         * and trimmed on every single sync, forever.
         */
        private const val KEEP = 130

        /**
         * Per-run fetch cap. A first sync of a hundred issues, each with attachments, would
         * otherwise hold the refresh open for minutes. The rest arrive on the next one.
         */
        private const val FETCH_PER_SYNC = 20
        private const val MAX_FETCH_ATTEMPTS = 3
        private const val INLINE_BUDGET_BYTES = 400_000
        private const val SQL_CHUNK = 400

        private const val MAX_TITLE = 600
        private const val MAX_AUTHOR = 300
        private const val MAX_SUMMARY = 12_000
        private const val MAX_CONTENT = 100_000
    }
}
