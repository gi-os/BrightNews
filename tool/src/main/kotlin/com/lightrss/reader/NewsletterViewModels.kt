package com.lightrss.reader

import androidx.lifecycle.viewModelScope
import com.lightrss.reader.gmail.GmailAuthState
import com.lightrss.reader.gmail.GmailLabel
import com.thelightphone.sdk.LightViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** The two shapes a newsletter can be shown in, and the one case where there is nothing to show. */
sealed interface NewsletterBody {
    data class Html(val document: String) : NewsletterBody
    data class Text(val body: String) : NewsletterBody
    data object Missing : NewsletterBody
}

/**
 * Holds one newsletter, rewritten for the panel.
 *
 * The rewrite runs on every change to the render mode, the ad filter or the image setting
 * rather than being cached, because it is a jsoup pass over a document already in memory and
 * the alternative is a cache that has to be invalidated from four places.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class NewsletterReaderViewModel(
    private val articleId: String,
    private val repository: RssRepository,
) : LightViewModel<Unit>() {

    private val newsletters = repository.newsletters

    val article: StateFlow<ArticleRow?> = repository.observeArticle(articleId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val renderMode: StateFlow<RenderMode> = newsletters.renderMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RenderMode.DARK)

    /**
     * Null until the screen has probed for a WebView provider. Nothing renders before then, so
     * a device without one never briefly shows an empty browser.
     */
    private val _webViewAvailable = MutableStateFlow<Boolean?>(null)

    fun setWebViewAvailable(available: Boolean) {
        _webViewAvailable.value = available
    }

    val body: StateFlow<NewsletterBody?> = combine(
        article,
        renderMode,
        newsletters.blockAds,
        repository.imagesEnabled,
        _webViewAvailable,
    ) { row, mode, blockAds, images, webView ->
        Triple(row, mode, Triple(blockAds, images, webView))
    }.flatMapLatest { (row, mode, rest) ->
        val (blockAds, images, webView) = rest
        if (row == null || webView == null) return@flatMapLatest flowOf<NewsletterBody?>(null)
        flow<NewsletterBody?> { emit(render(row, mode, blockAds, images, webView)) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Nothing in here may throw. jsoup can give up on a sufficiently broken document and Room
     * can throw on a disk error, and this is collected into composition — an exception takes
     * the process down instead of showing an error.
     */
    private suspend fun render(
        row: ArticleRow,
        mode: RenderMode,
        blockAds: Boolean,
        loadImages: Boolean,
        webViewAvailable: Boolean,
    ): NewsletterBody = withContext(Dispatchers.IO) {
        runCatching {
            val meta = ArticleMeta(
                subject = row.article.title,
                from = row.article.author.ifBlank { row.feedTitle },
                date = fullDate(row.article.publishedAt),
            )
            val html = newsletters.body(articleId)?.takeIf { it.isNotBlank() }
                ?: return@runCatching fallback(row)
            if (webViewAvailable) {
                NewsletterBody.Html(NewsletterHtml.rewrite(html, mode, loadImages, blockAds, meta))
            } else {
                NewsletterBody.Text(NewsletterHtml.toReadableText(html, meta, blockAds))
            }
        }.getOrElse { fallback(row) }
    }

    /**
     * The plain-text alternative, or the snippet. Reached when the message had no HTML part at
     * all, and again when the rewrite gives up — a broken document should cost the styling, not
     * the issue.
     */
    private fun fallback(row: ArticleRow): NewsletterBody {
        val text = row.article.content.ifBlank { row.article.summary }
        if (text.isBlank()) return NewsletterBody.Missing
        return NewsletterBody.Text(
            "${row.article.title}\n" +
                "${row.article.author} · ${fullDate(row.article.publishedAt)}\n\n$text",
        )
    }

    init {
        // Reading it is what marks it read, here and in Gmail. See NewsletterSync.markRead.
        viewModelScope.launch(Dispatchers.IO) { repository.setRead(articleId, true) }
    }

    fun toggleMode() {
        val next = if (renderMode.value == RenderMode.DARK) RenderMode.PAPER else RenderMode.DARK
        viewModelScope.launch(Dispatchers.IO) { newsletters.setRenderMode(next) }
    }

    fun toggleStar() {
        val current = article.value?.article ?: return
        viewModelScope.launch(Dispatchers.IO) {
            repository.setStarred(articleId, !current.isStarred)
        }
    }

    fun toggleRead() {
        val current = article.value?.article ?: return
        viewModelScope.launch(Dispatchers.IO) { repository.setRead(articleId, !current.isRead) }
    }

    fun archive(onArchived: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.setArchived(articleId, true)
            withContext(Dispatchers.Main) { onArchived() }
        }
    }
}

/** The mailbox screen: which account, which labels, and how newsletters are rendered. */
class MailboxViewModel(private val repository: RssRepository) : LightViewModel<Unit>() {

    private val newsletters = repository.newsletters

    val auth: StateFlow<GmailAuthState> = newsletters.auth.state
    val labels: StateFlow<List<FeedRow>> = repository.observeFeeds(Source.GMAIL)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val renderMode: StateFlow<RenderMode> = newsletters.renderMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RenderMode.DARK)
    val blockAds: StateFlow<Boolean> = newsletters.blockAds
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    init {
        viewModelScope.launch(Dispatchers.IO) { newsletters.auth.refreshState() }
    }

    fun toggleMode() {
        val next = if (renderMode.value == RenderMode.DARK) RenderMode.PAPER else RenderMode.DARK
        viewModelScope.launch(Dispatchers.IO) { newsletters.setRenderMode(next) }
    }

    fun toggleAds() {
        val next = !blockAds.value
        viewModelScope.launch(Dispatchers.IO) { newsletters.setBlockAds(next) }
    }

    fun signOut() {
        viewModelScope.launch(Dispatchers.IO) { newsletters.signOut() }
    }

    /** Sign out and forget the OAuth client too, so a wrong one can be replaced. */
    fun forget() {
        viewModelScope.launch(Dispatchers.IO) { newsletters.signOut(forgetClient = true) }
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) { repository.refreshAll(force = true) }
    }
}

data class ClientIdUiState(
    val inputSession: Int = 0,
    val error: String? = null,
)

/**
 * Takes the OAuth client id, or a whole credentials blob from `scripts/authorize.py`.
 *
 * Both arrive the same way — typed, pasted, or scanned off another screen as a QR code — so one
 * screen handles both and tells them apart by whether the text parses as JSON.
 */
class ClientIdViewModel(private val repository: RssRepository) : LightViewModel<Unit>() {
    private val _state = MutableStateFlow(ClientIdUiState())
    val state = _state.asStateFlow()

    fun clearError() = _state.update { it.copy(error = null) }

    fun submit(raw: CharSequence, onAccepted: () -> Unit) {
        val text = raw.toString().trim()
        if (text.isEmpty()) {
            reject("Enter or scan an OAuth client id.")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val auth = repository.newsletters.auth
            val accepted = try {
                val json = runCatching { JSONObject(text) }.getOrNull()
                if (json != null) auth.setCredentials(json) else auth.setClientId(text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                false
            }
            withContext(Dispatchers.Main) {
                if (accepted) {
                    onAccepted()
                } else {
                    reject(
                        "That is not a Google OAuth client id.\n\n" +
                            "It ends in .apps.googleusercontent.com.",
                    )
                }
            }
        }
    }

    private fun reject(message: String) {
        _state.update { it.copy(error = message, inputSession = it.inputSession + 1) }
    }
}

data class SignInUiState(
    val url: String? = null,
    val finishing: Boolean = false,
    val error: String? = null,
)

/** Drives the consent WebView: builds the URL, and takes the code off the redirect. */
class GmailSignInViewModel(private val repository: RssRepository) : LightViewModel<Boolean>() {
    private val _state = MutableStateFlow(SignInUiState())
    val state = _state.asStateFlow()

    /** Guards against a redirect the WebView reports twice. */
    private var consumed = false

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val auth = repository.newsletters.auth
            val url = runCatching { auth.authorizationUrl() }.getOrNull()
            _state.update {
                it.copy(url = url, error = if (url == null) "Could not build the sign-in link." else null)
            }
        }
    }

    fun complete(url: String, onDone: (Boolean) -> Unit) {
        if (consumed) return
        consumed = true
        _state.update { it.copy(finishing = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val ok = runCatching { repository.newsletters.auth.onRedirect(url) }.getOrDefault(false)
            if (ok) repository.refreshAll(force = true)
            withContext(Dispatchers.Main) { onDone(ok) }
        }
    }
}

data class LabelPickerUiState(
    val isLoading: Boolean = true,
    val labels: List<GmailLabel> = emptyList(),
    val error: String? = null,
)

class LabelPickerViewModel(private val repository: RssRepository) : LightViewModel<Long>() {
    private val _state = MutableStateFlow(LabelPickerUiState())
    val state = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val labels = repository.newsletters.listLabels()
                _state.update { it.copy(isLoading = false, labels = labels) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _state.update {
                    it.copy(isLoading = false, error = RssRepository.friendlyMessage(error))
                }
            }
        }
    }

    fun subscribe(label: GmailLabel, onAdded: (Long) -> Unit, onError: (String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val feedId = repository.newsletters.addLabel(label)
                repository.refreshFeed(feedId)
                withContext(Dispatchers.Main) { onAdded(feedId) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                withContext(Dispatchers.Main) { onError(RssRepository.friendlyMessage(error)) }
            }
        }
    }
}
