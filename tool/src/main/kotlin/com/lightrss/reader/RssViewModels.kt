package com.lightrss.reader

import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SimpleLightScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The two sections, and the counts beside them.
 *
 * Home is a chooser rather than a list because the two sources read differently — an RSS item is
 * a headline you skim past, a newsletter is a thing you sat down for — and merging them into one
 * timeline buries the second under the first on any day with a busy feed. The counts are what
 * make the extra tap worth it: you can see whether there is anything in either without opening
 * one.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HomeViewModel(
    val repository: RssRepository,
    private val database: RssDatabase,
) : LightViewModel<Unit>() {

    /**
     * Which section the list is showing. Not persisted on purpose: the app opens on RSS because
     * that is the one with something new in it most days, and a remembered tab means opening to
     * whatever you happened to be looking at last week.
     */
    private val _section = MutableStateFlow(Source.RSS)
    val section: StateFlow<String> = _section.asStateFlow()

    fun showSection(source: String) {
        if (_section.value == source) return
        _section.value = source
        _jumpToNewest.update { it + 1 }
    }

    val unreadOnly: StateFlow<Boolean> = repository.homeUnreadOnly
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val favoritesOnly: StateFlow<Boolean> = repository.homeFavoritesOnly
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val favoriteFeedCount: StateFlow<Int> = repository.observeFavoriteFeedCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val articles: StateFlow<List<ArticleRow>> =
        combine(_section, unreadOnly, favoritesOnly) { section, unread, favourites ->
            // Favourites are an RSS idea; a mailbox has no starred labels to narrow to.
            Triple(section, unread, favourites && section == Source.RSS)
        }.flatMapLatest { (section, unread, favourites) ->
            repository.observeInbox(unread, favourites, section)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Bumped when the list should go back to the newest item: on first show, after the app has
     * been away, and on a section change — switching tabs and landing halfway down the new one
     * would be disorienting in a way that returning from the reader is not.
     */
    private val _jumpToNewest = MutableStateFlow(0)
    val jumpToNewest: StateFlow<Int> = _jumpToNewest.asStateFlow()
    private var jumpPending = true

    override fun onAppPause() {
        super.onAppPause()
        jumpPending = true
    }

    val rssUnread: StateFlow<Int> = repository.observeUnreadCount(Source.RSS)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val newsletterUnread: StateFlow<Int> = repository.observeUnreadCount(Source.GMAIL)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val feedCount: StateFlow<Int> = repository.observeFeeds(Source.RSS)
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val labelCount: StateFlow<Int> = repository.observeFeeds(Source.GMAIL)
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val syncState: StateFlow<SyncState> = repository.syncState

    private var initialized = false

    init {
        viewModelScope.launch(Dispatchers.IO) {
            repository.initialize()
            initialized = true
            repository.refreshAll(force = false)
        }
    }

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        if (jumpPending) {
            jumpPending = false
            _jumpToNewest.update { it + 1 }
        }
        if (initialized) {
            viewModelScope.launch(Dispatchers.IO) { repository.refreshAll(force = false) }
        }
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) { repository.refreshAll(force = true) }
    }

    /**
     * Home owns the repository and the database because it is the one screen guaranteed to
     * outlive every other — closing them anywhere else would pull the connection out from under
     * a section still on the stack.
     */
    override fun onCleared() {
        repository.close()
        database.close()
        super.onCleared()
    }
}

class FeedsViewModel(private val repository: RssRepository) : LightViewModel<Unit>() {
    val feeds: StateFlow<List<FeedRow>> = repository.observeFeeds(Source.RSS)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The home scope, shown and flipped from the rows above the subscription list. */
    val favoritesOnly: StateFlow<Boolean> = repository.homeFavoritesOnly
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val unreadOnly: StateFlow<Boolean> = repository.homeUnreadOnly
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    fun toggleUnreadOnly() {
        val next = !unreadOnly.value
        viewModelScope.launch(Dispatchers.IO) { repository.setHomeUnreadOnly(next) }
    }

    fun toggleHomeScope() {
        val next = !favoritesOnly.value
        viewModelScope.launch(Dispatchers.IO) { repository.setHomeFavoritesOnly(next) }
    }
}

data class AddFeedUiState(
    val draft: String = "",
    val inputSession: Int = 0,
    val isAdding: Boolean = false,
    /** Set from a scan or add failure; the screen shows it and then calls [AddFeedViewModel.clearError]. */
    val error: String? = null,
)

/** No-state viewmodel for screens that only route and return a new feed's id. */
class ChooserViewModel : LightViewModel<Long>()

/** The same, for a menu that returns nothing. */
class MenuViewModel : LightViewModel<Unit>()

class AddFeedViewModel(private val repository: RssRepository) : LightViewModel<Long>() {
    private val _state = MutableStateFlow(AddFeedUiState())
    val state = _state.asStateFlow()

    /**
     * Adds the feed encoded in a scanned QR code. Called from the camera analyzer, so it never
     * navigates directly: failures land in [AddFeedUiState.error] for the screen to present.
     */
    fun addScannedFeed(scanned: String, onAdded: (Long) -> Unit) {
        if (_state.value.isAdding) return
        val url = try {
            RssParser.feedUrlFromScan(scanned)
        } catch (error: IllegalArgumentException) {
            reportError(error.message ?: "That QR code was not a feed address.")
            return
        }
        addFeed(url, onAdded) { message -> reportError(message) }
    }

    fun reportError(message: String) {
        _state.update { it.copy(isAdding = false, error = message, inputSession = it.inputSession + 1) }
    }

    /** Clears a shown error and re-arms the scanner or editor for another attempt. */
    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    fun addFeed(
        rawUrl: CharSequence,
        onAdded: (Long) -> Unit,
        onError: (String) -> Unit,
    ) {
        val url = rawUrl.toString().trim()
        if (url.isEmpty()) {
            _state.update { it.copy(draft = url, inputSession = it.inputSession + 1) }
            onError("Enter a website or RSS feed address.")
            return
        }
        _state.update { it.copy(draft = url, isAdding = true) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val feedId = repository.addFeed(url)
                withContext(Dispatchers.Main) { onAdded(feedId) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _state.update {
                    it.copy(
                        inputSession = it.inputSession + 1,
                        isAdding = false,
                    )
                }
                withContext(Dispatchers.Main) {
                    onError(RssRepository.friendlyMessage(error))
                }
            }
        }
    }
}

data class FeedUiState(
    val isRefreshing: Boolean = false,
    val message: String? = null,
)

class FeedViewModel(
    private val feedId: Long,
    private val repository: RssRepository,
) : LightViewModel<Unit>() {
    val feed: StateFlow<FeedEntity?> = repository.observeFeed(feedId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val articles: StateFlow<List<ArticleRow>> = repository.observeFeedArticles(feedId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val _state = MutableStateFlow(FeedUiState())
    val state = _state.asStateFlow()

    fun refresh() {
        if (_state.value.isRefreshing) return
        _state.update { it.copy(isRefreshing = true, message = null) }
        viewModelScope.launch(Dispatchers.IO) {
            val result = repository.refreshFeed(feedId)
            _state.update {
                it.copy(
                    isRefreshing = false,
                    message = result.fold(
                        onSuccess = { "Up to date" },
                        onFailure = RssRepository::friendlyMessage,
                    ),
                )
            }
        }
    }

    fun markAllRead() {
        viewModelScope.launch(Dispatchers.IO) {
            repository.markFeedRead(feedId)
            _state.update { it.copy(message = "Marked as read") }
        }
    }

    /** Stars or unstars this feed for the favourites-only home list. */
    fun toggleFavorite() {
        val next = !(feed.value?.isFavorite ?: false)
        viewModelScope.launch(Dispatchers.IO) {
            repository.setFeedFavorite(feedId, next)
            _state.update {
                it.copy(message = if (next) "Shown on home" else "Hidden from home")
            }
        }
    }
}

class DeleteFeedViewModel(
    private val feedId: Long,
    private val repository: RssRepository,
) : LightViewModel<Unit>() {
    private val _isDeleting = MutableStateFlow(false)
    val isDeleting = _isDeleting.asStateFlow()

    fun delete(
        onDeleted: () -> Unit,
        onError: (String) -> Unit,
    ) {
        if (_isDeleting.value) return
        _isDeleting.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                repository.deleteFeed(feedId)
                withContext(Dispatchers.Main) { onDeleted() }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _isDeleting.value = false
                withContext(Dispatchers.Main) {
                    onError(RssRepository.friendlyMessage(error))
                }
            }
        }
    }
}

class SavedViewModel(repository: RssRepository) : LightViewModel<Unit>() {
    val articles: StateFlow<List<ArticleRow>> = repository.observeStarred()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

data class SearchUiState(
    val editorOpen: Boolean = true,
    val editorSession: Int = 0,
    val query: String = "",
)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SearchViewModel(private val repository: RssRepository) : LightViewModel<Unit>() {
    private val _state = MutableStateFlow(SearchUiState())
    val state = _state.asStateFlow()
    val results: StateFlow<List<ArticleRow>> = _state
        .flatMapLatest { state ->
            if (state.query.isBlank()) flowOf(emptyList()) else repository.search(state.query)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun submit(rawQuery: CharSequence) {
        val query = rawQuery.toString().trim()
        if (query.isNotEmpty()) _state.update { it.copy(editorOpen = false, query = query) }
    }

    fun edit() = _state.update { it.copy(editorOpen = true, editorSession = it.editorSession + 1) }
}

class ReaderViewModel(
    private val articleId: String,
    private val repository: RssRepository,
) : LightViewModel<Unit>() {
    val article: StateFlow<ArticleRow?> = repository.observeArticle(articleId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        viewModelScope.launch(Dispatchers.IO) { repository.setRead(articleId, true) }
    }

    fun toggleStar() {
        val current = article.value?.article ?: return
        viewModelScope.launch(Dispatchers.IO) { repository.setStarred(articleId, !current.isStarred) }
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

data class ReaderPageUiState(
    val isLoading: Boolean = true,
    val page: ReaderPage? = null,
    val error: String? = null,
    /** Where the article actually resolved to, which is what the browser should open. */
    val resolvedUrl: String = "",
)

/** Fetches and holds the reader-mode version of an article's linked page. */
class ReaderPageViewModel(
    private val articleId: String,
    private val url: String,
    private val repository: RssRepository,
) : LightViewModel<Unit>() {
    private val _state = MutableStateFlow(ReaderPageUiState())
    val state = _state.asStateFlow()

    init {
        load(refresh = false)
    }

    fun retry() = load(refresh = true)

    private fun load(refresh: Boolean) {
        if (_state.value.isLoading && _state.value.page == null && refresh) return
        _state.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = repository.readerPage(articleId, url, refresh)
                val readable = with(ReaderExtractor) { result.page.hasContent() } && !result.gated
                _state.update {
                    it.copy(
                        isLoading = false,
                        page = result.page.takeIf { readable },
                        resolvedUrl = result.url,
                        error = when {
                            readable -> null
                            result.gated ->
                                "This site is checking your browser or asking you to sign in. " +
                                    "Open it in a browser to get through, then try again."
                            else -> "This page did not give up any readable text."
                        },
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _state.update {
                    it.copy(isLoading = false, error = RssRepository.friendlyMessage(error))
                }
            }
        }
    }
}

class SettingsViewModel(private val repository: RssRepository) : LightViewModel<Unit>() {
    val imagesEnabled: StateFlow<Boolean> = repository.imagesEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val colourEnabled: StateFlow<Boolean> = repository.colourEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    fun setColourEnabled(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) { repository.setColourEnabled(enabled) }
    }

    fun setImagesEnabled(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) { repository.setImagesEnabled(enabled) }
    }

    fun clearImages() {
        viewModelScope.launch(Dispatchers.IO) { repository.clearImageCache() }
    }

    fun markAllRead() {
        viewModelScope.launch(Dispatchers.IO) {
            repository.markAllRead()
        }
    }

    fun clearRead() {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteReadUnstarred()
        }
    }
}
