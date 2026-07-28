package com.lightrss.reader

import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SimpleLightScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HomeViewModel(
    val repository: RssRepository,
    private val database: RssDatabase,
) : LightViewModel<Unit>() {
    private val _unreadOnly = MutableStateFlow(true)
    val unreadOnly: StateFlow<Boolean> = _unreadOnly.asStateFlow()
    val articles: StateFlow<List<ArticleRow>> = _unreadOnly
        .flatMapLatest(repository::observeInbox)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
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
        if (initialized) {
            viewModelScope.launch(Dispatchers.IO) { repository.refreshAll(force = false) }
        }
    }

    fun toggleFilter() = _unreadOnly.update { !it }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) { repository.refreshAll(force = true) }
    }

    override fun onCleared() {
        repository.close()
        database.close()
        super.onCleared()
    }
}

class FeedsViewModel(private val repository: RssRepository) : LightViewModel<Unit>() {
    val feeds: StateFlow<List<FeedRow>> = repository.observeFeeds()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

data class AddFeedUiState(
    val draft: String = "",
    val inputSession: Int = 0,
    val isAdding: Boolean = false,
    /** Set from a scan or add failure; the screen shows it and then calls [AddFeedViewModel.clearError]. */
    val error: String? = null,
)

/** No-state viewmodel for screens that only route, such as the add-feed chooser. */
class ChooserViewModel : LightViewModel<Long>()

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

class SettingsViewModel(private val repository: RssRepository) : LightViewModel<Unit>() {
    val imagesEnabled: StateFlow<Boolean> = repository.imagesEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

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
