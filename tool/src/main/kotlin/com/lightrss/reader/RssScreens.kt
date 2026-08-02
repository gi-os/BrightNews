package com.lightrss.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import com.lightrss.reader.hw.WheelScroll
import com.lightrss.reader.hw.WheelKeys
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.buildDatabase
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightFullscreenModal
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable

/**
 * The two sections.
 *
 * RSS and newsletters are one app because they are one habit, but they are two sections because
 * they are not one kind of reading. Everything below this screen is shared — the same article
 * table, the same search, the same saved list, the same read state — and the split is a filter
 * on `sourceType`, not a second stack.
 */
@InitialScreen
class HomeScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, HomeViewModel>(sealedActivity) {
    override val viewModelClass: Class<HomeViewModel> = HomeViewModel::class.java

    override fun createViewModel(): HomeViewModel {
        val database = lightContext.buildDatabase(
            RssDatabase::class.java,
            // The file keeps its name across the rename. Pointing at a new one would silently
            // orphan every subscription and every saved article on the update.
            "light-rss.db",
            RssDatabase.MIGRATION_1_2,
            RssDatabase.MIGRATION_2_3,
            RssDatabase.MIGRATION_3_4,
        )
        val repository = RssRepository(
            dao = database.rssDao(),
            imageCacheDir = lightContext.filesDir,
        )
        return HomeViewModel(repository, database)
    }

    @Composable
    override fun Content() {
        val colors by LightThemeController.colors.collectAsState()
        val rssUnread by viewModel.rssUnread.collectAsState()
        val newsletterUnread by viewModel.newsletterUnread.collectAsState()
        val feedCount by viewModel.feedCount.collectAsState()
        val labelCount by viewModel.labelCount.collectAsState()
        val sync by viewModel.syncState.collectAsState()
        val repository = viewModel.repository

        WheelKeys()
        LightTheme(colors = colors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(
                        icon = LightIcons.SETTINGS,
                        onClick = { navigateTo({ SettingsScreen(it, repository) }) },
                    ),
                    center = LightTopBarCenter.Text("News"),
                    rightButton = LightBarButton.LightIcon(
                        icon = LightIcons.SEARCH,
                        onClick = { navigateTo({ SearchScreen(it, repository) }) },
                        contentDescription = "Search everything",
                    ),
                )
                StatusLine(
                    when {
                        sync.isRefreshing -> "SYNC ${sync.completedFeeds}/${sync.totalFeeds}"
                        sync.message?.contains("could not", ignoreCase = true) == true -> sync.message
                        else -> null
                    },
                )
                Column(modifier = Modifier.weight(1f)) {
                    SectionRow(
                        title = "RSS",
                        unread = rssUnread,
                        detail = when (feedCount) {
                            0 -> "No feeds yet"
                            1 -> "1 feed"
                            else -> "$feedCount feeds"
                        },
                    ) {
                        navigateTo({ SectionScreen(it, Source.RSS, repository) })
                    }
                    SectionRow(
                        title = "NEWSLETTERS",
                        unread = newsletterUnread,
                        detail = when (labelCount) {
                            0 -> "Not set up — open to connect Gmail"
                            1 -> "1 label"
                            else -> "$labelCount labels"
                        },
                    ) {
                        navigateTo({ SectionScreen(it, Source.GMAIL, repository) })
                    }
                }
                LightBottomBar(
                    items = listOf(
                        LightBarButton.LightIcon(
                            icon = LightIcons.STAR_OUTLINE,
                            onClick = { navigateTo({ SavedScreen(it, repository) }) },
                            contentDescription = "Saved",
                        ),
                        LightBarButton.LightIcon(
                            icon = LightIcons.REFRESH,
                            onClick = viewModel::refresh,
                        ),
                    ),
                )
            }
        }
    }
}

/**
 * A section on the home screen.
 *
 * Taller than a settings row and headed in [LightTextVariant.Subheading], because these two are
 * the app's top-level choice rather than an option inside it. The unread count is part of the
 * detail line rather than a badge: the panel is monochrome, so a badge is just a smaller number
 * in a harder place to read.
 */
@Composable
private fun SectionRow(
    title: String,
    unread: Int,
    detail: String,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClickLabel = title, role = Role.Button) { onClick() }
            .padding(horizontal = 1f.gridUnitsAsDp(), vertical = 1.25f.gridUnitsAsDp()),
    ) {
        LightText(title, LightTextVariant.Subheading, maxLines = 1, overflow = TextOverflow.Ellipsis)
        LightText(
            text = listOf(
                when (unread) {
                    0 -> "All read"
                    1 -> "1 unread"
                    else -> "$unread unread"
                },
                detail,
            ).joinToString(" · "),
            variant = LightTextVariant.Superfine,
            lighten = true,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 0.4f.gridUnitsAsDp()),
        )
    }
}

/**
 * One section's articles: the list this app used to open straight into, now scoped to a source.
 *
 * Both sections are this screen. The differences are two words in the top bar and where the
 * list button goes — Subscriptions for feeds, Mailbox for labels — because past the source
 * filter there is nothing about a newsletter that a list of articles needs to know.
 */
class SectionScreen(
    sealedActivity: SealedLightActivity,
    private val source: String,
    private val repository: RssRepository,
) : LightScreen<Unit, SectionViewModel>(sealedActivity) {
    override val viewModelClass: Class<SectionViewModel> = SectionViewModel::class.java
    override fun createViewModel() = SectionViewModel(source, repository)

    @Composable
    override fun Content() {
        val colors by LightThemeController.colors.collectAsState()
        val articles by viewModel.articles.collectAsState()
        val unreadOnly by viewModel.unreadOnly.collectAsState()
        val favoritesOnly by viewModel.favoritesOnly.collectAsState()
        val favoriteCount by viewModel.favoriteFeedCount.collectAsState()
        val sync by viewModel.syncState.collectAsState()
        val jumpToNewest by viewModel.jumpToNewest.collectAsState()
        val imageStore = rememberImageStore(repository)
        val listState = rememberLazyListState()
        val newsletters = source == Source.GMAIL

        // Opening the section lands on the newest item, even when a sync has just slipped fresh
        // ones in above where the list was left.
        LaunchedEffect(jumpToNewest) {
            if (jumpToNewest > 0) listState.scrollToItem(0)
        }

        WheelKeys()
        LightTheme(colors = colors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                key(unreadOnly, favoritesOnly) {
                    LightTopBar(
                        leftButton = LightBarButton.LightIcon(LightIcons.BACK, onClick = { goBack() }),
                        center = LightTopBarCenter.Text(
                            when {
                                newsletters -> "Newsletters"
                                favoritesOnly -> "Favorites"
                                else -> "RSS"
                            },
                        ),
                        rightButton = LightBarButton.LightIcon(
                            icon = LightIcons.LIST,
                            onClick = {
                                if (newsletters) {
                                    navigateTo({ MailboxScreen(it, repository) })
                                } else {
                                    navigateTo({ FeedsScreen(it, repository) })
                                }
                            },
                            contentDescription = if (newsletters) "Mailbox" else "Subscriptions",
                        ),
                    )
                }
                StatusLine(
                    when {
                        sync.isRefreshing -> "SYNC ${sync.completedFeeds}/${sync.totalFeeds}"
                        sync.message?.contains("could not", ignoreCase = true) == true -> sync.message
                        else -> null
                    },
                )
                ArticleList(
                    articles = articles,
                    emptyMessage = emptyMessage(newsletters, unreadOnly, favoritesOnly, favoriteCount),
                    onOpen = { row ->
                        navigateTo({ articleReader(it, row.article.id, repository) })
                    },
                    modifier = Modifier.weight(1f),
                    imageStore = imageStore,
                    listState = listState,
                )
                LightBottomBar(
                    items = listOf(
                        LightBarButton.Text(
                            text = if (unreadOnly) "UNREAD" else "ALL",
                            onClick = viewModel::toggleFilter,
                        ),
                        LightBarButton.LightIcon(
                            icon = LightIcons.REFRESH,
                            onClick = viewModel::refresh,
                        ),
                    ),
                )
            }
        }
    }

    private fun emptyMessage(
        newsletters: Boolean,
        unreadOnly: Boolean,
        favoritesOnly: Boolean,
        favoriteCount: Int,
    ): String = when {
        newsletters && unreadOnly ->
            "Nothing unread.\n\nFollow a Gmail label from the list button, or switch the " +
                "filter to revisit past issues."
        newsletters ->
            "No newsletters yet.\n\nOpen the list button to connect Gmail and follow a label."
        favoritesOnly && favoriteCount == 0 ->
            "No favorite feeds yet.\n\nStar a feed in Subscriptions, or switch back to all feeds."
        favoritesOnly && unreadOnly ->
            "You’re all caught up on your favorites.\n\nSwitch the filter to revisit the archive."
        unreadOnly ->
            "You’re all caught up.\n\nSwitch the filter to revisit the archive."
        else -> "No articles yet.\n\nRefresh or add a subscription."
    }
}

/**
 * The right reader for an article.
 *
 * Decided from the id rather than by branching at every call site: five screens open articles,
 * and each of them would otherwise have to know that newsletters exist.
 */
internal fun articleReader(
    activity: SealedLightActivity,
    articleId: String,
    repository: RssRepository,
): SimpleLightScreen<Unit> =
    if (NewsletterSync.isNewsletter(articleId)) {
        NewsletterReaderScreen(activity, articleId, repository)
    } else {
        ReaderScreen(activity, articleId, repository)
    }

/** The image store to render with, or null while images are switched off in Settings. */
@Composable
private fun rememberImageStore(repository: RssRepository): ArticleImageStore? {
    val enabled by repository.imagesEnabled.collectAsState(initial = true)
    return repository.images.takeIf { enabled }
}

class FeedsScreen(
    sealedActivity: SealedLightActivity,
    private val repository: RssRepository,
) : LightScreen<Unit, FeedsViewModel>(sealedActivity) {
    override val viewModelClass: Class<FeedsViewModel> = FeedsViewModel::class.java
    override fun createViewModel() = FeedsViewModel(repository)

    @Composable
    override fun Content() {
        val colors by LightThemeController.colors.collectAsState()
        val feeds by viewModel.feeds.collectAsState()
        val favoritesOnly by viewModel.favoritesOnly.collectAsState()

        WheelKeys()
        LightTheme(colors = colors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(LightIcons.BACK, onClick = { goBack() }),
                    center = LightTopBarCenter.Text("Subscriptions"),
                    rightButton = LightBarButton.LightIcon(
                        LightIcons.ADD,
                        onClick = {
                            navigateTo({ AddFeedChooserScreen(it, repository) }) { feedId ->
                                navigateTo({ FeedScreen(it, feedId, repository) })
                            }
                        },
                    ),
                )
                SettingsRow(
                    title = "HOME SHOWS",
                    detail = if (favoritesOnly) {
                        "Favorites only — tap for all feeds"
                    } else {
                        "All feeds — tap for favorites only"
                    },
                    onClick = viewModel::toggleHomeScope,
                )
                FeedList(
                    feeds = feeds,
                    onOpen = { row -> navigateTo({ FeedScreen(it, row.feed.id, repository) }) },
                    modifier = Modifier.weight(1f),
                )
                LightBottomBar(
                    items = listOf(
                        LightBarButton.LightIcon(
                            icon = LightIcons.STAR_OUTLINE,
                            onClick = { navigateTo({ SavedScreen(it, repository) }) },
                            contentDescription = "Saved articles",
                        ),
                        LightBarButton.LightIcon(
                            icon = LightIcons.SETTINGS,
                            onClick = { navigateTo({ SettingsScreen(it, repository) }) },
                        ),
                    ),
                )
            }
        }
    }
}

/** Chooser shown by the + button: scan a QR code, or type an address on the Light keyboard. */
class AddFeedChooserScreen(
    sealedActivity: SealedLightActivity,
    private val repository: RssRepository,
) : LightScreen<Long, ChooserViewModel>(sealedActivity) {
    override val viewModelClass: Class<ChooserViewModel> = ChooserViewModel::class.java
    override fun createViewModel() = ChooserViewModel()

    @Composable
    override fun Content() {
        val colors by LightThemeController.colors.collectAsState()
        LightTheme(colors = colors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(LightIcons.BACK, onClick = { goBack() }),
                    center = LightTopBarCenter.Text("Add feed"),
                )
                SettingsRow("SCAN QR CODE", "Point the camera at a feed code") {
                    navigateTo({ ScanFeedScreen(it, repository) }) { feedId -> goBack(feedId) }
                }
                SettingsRow("PASTE OR TYPE", "Phone keyboard, with a paste button") {
                    navigateTo({ AddFeedScreen(it, repository) }) { feedId -> goBack(feedId) }
                }
                Column(
                    modifier = Modifier.padding(
                        start = 1f.gridUnitsAsDp(),
                        end = 1f.gridUnitsAsDp(),
                        top = 1f.gridUnitsAsDp(),
                    ),
                ) {
                    LightText("MAKE A CODE", LightTextVariant.Superfine, lighten = true)
                    LightText(
                        text = "Turn any feed address into a QR code on another device at",
                        variant = LightTextVariant.Detail,
                        modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp()),
                    )
                    LightText(
                        text = QR_GENERATOR_URL,
                        variant = LightTextVariant.Detail,
                        modifier = Modifier.padding(top = 0.25f.gridUnitsAsDp()),
                    )
                }
            }
        }
    }
}

/**
 * Adds a subscription from a QR code containing a feed or website address.
 *
 * The camera is driven by [FeedQrScanner], which owns its CameraX controller the way the
 * LightPass camera screens do, rather than by the SDK scanner composable.
 */
class ScanFeedScreen(
    sealedActivity: SealedLightActivity,
    private val repository: RssRepository,
) : LightScreen<Long, AddFeedViewModel>(sealedActivity) {
    override val viewModelClass: Class<AddFeedViewModel> = AddFeedViewModel::class.java
    override fun createViewModel() = AddFeedViewModel(repository)

    @Composable
    override fun Content() {
        val colors by LightThemeController.colors.collectAsState()
        val state by viewModel.state.collectAsState()

        // Add failures report through state so nothing navigates from the camera callback.
        LaunchedEffect(state.error) {
            val message = state.error ?: return@LaunchedEffect
            viewModel.clearError()
            navigateTo({ MessageScreen(it, message) })
        }

        LightTheme(colors = colors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(LightIcons.BACK, onClick = { goBack() }),
                    center = LightTopBarCenter.Text(if (state.isAdding) "Adding feed" else "Scan feed"),
                )
                if (state.isAdding) {
                    LoadingScreen("Finding feed\u2026", Modifier.weight(1f))
                } else {
                    // A fresh key re-arms the one-shot scan latch after a rejected code.
                    key(state.inputSession) {
                        FeedQrScanner(
                            onScanned = { scanned ->
                                viewModel.addScannedFeed(scanned) { feedId -> goBack(feedId) }
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    LightBottomBar(
                        items = listOf(
                            LightBarButton.Text("TYPE INSTEAD", onClick = {
                                navigateTo({ AddFeedScreen(it, repository) }) { id -> goBack(id) }
                            }),
                        ),
                    )
                }
            }
        }
    }
}

class AddFeedScreen(
    sealedActivity: SealedLightActivity,
    private val repository: RssRepository,
) : LightScreen<Long, AddFeedViewModel>(sealedActivity) {
    override val viewModelClass: Class<AddFeedViewModel> = AddFeedViewModel::class.java
    override fun createViewModel() = AddFeedViewModel(repository)

    @Composable
    override fun Content() {
        val colors by LightThemeController.colors.collectAsState()
        val state by viewModel.state.collectAsState()
        val input = key(state.inputSession) { rememberTextFieldState(state.draft) }
        val keyboard = rememberKeyboardOptions()
        var lightKeys by rememberSaveable { mutableStateOf(false) }

        LaunchedEffect(state.error) {
            val message = state.error ?: return@LaunchedEffect
            viewModel.clearError()
            navigateTo({ MessageScreen(it, message) })
        }

        val submit: (CharSequence) -> Unit = { raw ->
            viewModel.addFeed(
                rawUrl = raw,
                onAdded = { feedId -> goBack(feedId) },
                onError = viewModel::reportError,
            )
        }

        LightTheme(colors = colors) {
            when {
                state.isAdding -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(LightThemeTokens.colors.background),
                    ) {
                        LightTopBar(
                            leftButton = LightBarButton.LightIcon(LightIcons.BACK, onClick = { goBack() }),
                            center = LightTopBarCenter.Text("Feed URL"),
                        )
                        LoadingScreen("Finding feed\u2026", Modifier.weight(1f))
                    }
                }

                lightKeys -> {
                    LightTextInputEditor(
                        title = "Feed URL",
                        editorKey = state.inputSession,
                        keyboardOptionsFlow = keyboard,
                        state = input,
                        onSubmit = submit,
                        onBack = { lightKeys = false },
                        submitIcon = LightIcons.ADD,
                        showBackButton = true,
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                else -> {
                    SystemTextEntry(
                        title = "Feed URL",
                        state = input,
                        submitLabel = "ADD",
                        hint = "https://example.com/feed.xml",
                        onSubmit = submit,
                        onBack = { goBack() },
                        onUseLightKeys = { lightKeys = true },
                    )
                }
            }
        }
    }
}

class FeedScreen(
    sealedActivity: SealedLightActivity,
    private val feedId: Long,
    private val repository: RssRepository,
) : LightScreen<Unit, FeedViewModel>(sealedActivity) {
    override val viewModelClass: Class<FeedViewModel> = FeedViewModel::class.java
    override fun createViewModel() = FeedViewModel(feedId, repository)

    @Composable
    override fun Content() {
        val colors by LightThemeController.colors.collectAsState()
        val feed by viewModel.feed.collectAsState()
        val articles by viewModel.articles.collectAsState()
        val state by viewModel.state.collectAsState()
        val imageStore = rememberImageStore(repository)

        WheelKeys()
        LightTheme(colors = colors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(LightIcons.BACK, onClick = { goBack() }),
                    center = LightTopBarCenter.Text(feed?.title ?: "Feed"),
                    rightButton = LightBarButton.LightIcon(LightIcons.REFRESH, onClick = viewModel::refresh),
                )
                StatusLine(
                    when {
                        state.isRefreshing -> "SYNC"
                        state.message != null -> state.message.takeUnless {
                            it == "Up to date" || it == "Marked as read"
                        }
                        else -> feed?.errorMessage
                    },
                )
                ArticleList(
                    articles = articles,
                    emptyMessage = "No articles are available from this feed.",
                    onOpen = { row -> navigateTo({ articleReader(it, row.article.id, repository) }) },
                    modifier = Modifier.weight(1f),
                    imageStore = imageStore,
                )
                LightBottomBar(
                    items = listOf(
                        LightBarButton.Text("READ ALL", onClick = viewModel::markAllRead),
                        LightBarButton.LightIcon(
                            icon = if (feed?.isFavorite == true) LightIcons.STAR else LightIcons.STAR_OUTLINE,
                            onClick = viewModel::toggleFavorite,
                            contentDescription = if (feed?.isFavorite == true) {
                                "Hide from home"
                            } else {
                                "Show on home"
                            },
                        ),
                        LightBarButton.LightIcon(
                            LightIcons.DELETE,
                            onClick = {
                                val title = feed?.title ?: "this feed"
                                navigateTo({ DeleteFeedScreen(it, feedId, title, repository) }) {
                                    goBack()
                                }
                            },
                        ),
                    ),
                )
            }
        }
    }
}

private class DeleteFeedScreen(
    sealedActivity: SealedLightActivity,
    private val feedId: Long,
    private val feedTitle: String,
    private val repository: RssRepository,
) : LightScreen<Unit, DeleteFeedViewModel>(sealedActivity) {
    override val viewModelClass: Class<DeleteFeedViewModel> = DeleteFeedViewModel::class.java
    override fun createViewModel() = DeleteFeedViewModel(feedId, repository)

    @Composable
    override fun Content() {
        val colors by LightThemeController.colors.collectAsState()
        val isDeleting by viewModel.isDeleting.collectAsState()
        LightTheme(colors = colors) {
            if (isDeleting) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(LightThemeTokens.colors.background),
                ) {
                    LightTopBar(
                        leftButton = LightBarButton.LightIcon(LightIcons.BACK, onClick = { goBack() }),
                        center = LightTopBarCenter.Text("Unfollow"),
                    )
                    LoadingScreen("Removing feed…", Modifier.weight(1f))
                }
            } else {
                ConfirmationContent(
                    message = "Unfollow $feedTitle?\n\nIts downloaded articles will be removed.",
                    confirmLabel = "UNFOLLOW",
                    onCancel = { goBack() },
                    onConfirm = {
                        viewModel.delete(
                            onDeleted = { goBack(Unit) },
                            onError = { message -> navigateTo({ MessageScreen(it, message) }) },
                        )
                    },
                )
            }
        }
    }
}

class SavedScreen(
    sealedActivity: SealedLightActivity,
    private val repository: RssRepository,
) : LightScreen<Unit, SavedViewModel>(sealedActivity) {
    override val viewModelClass: Class<SavedViewModel> = SavedViewModel::class.java
    override fun createViewModel() = SavedViewModel(repository)

    @Composable
    override fun Content() {
        val colors by LightThemeController.colors.collectAsState()
        val articles by viewModel.articles.collectAsState()
        val imageStore = rememberImageStore(repository)
        WheelKeys()
        LightTheme(colors = colors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(LightIcons.BACK, onClick = { goBack() }),
                    center = LightTopBarCenter.Text("Saved"),
                )
                ArticleList(
                    articles = articles,
                    emptyMessage = "No saved articles.\n\nUse the star while reading to keep something.",
                    onOpen = { row -> navigateTo({ articleReader(it, row.article.id, repository) }) },
                    modifier = Modifier.weight(1f),
                    imageStore = imageStore,
                )
            }
        }
    }
}

class SearchScreen(
    sealedActivity: SealedLightActivity,
    private val repository: RssRepository,
) : LightScreen<Unit, SearchViewModel>(sealedActivity) {
    override val viewModelClass: Class<SearchViewModel> = SearchViewModel::class.java
    override fun createViewModel() = SearchViewModel(repository)

    @Composable
    override fun Content() {
        val colors by LightThemeController.colors.collectAsState()
        val state by viewModel.state.collectAsState()
        val results by viewModel.results.collectAsState()
        val imageStore = rememberImageStore(repository)
        val input = key(state.editorSession) { rememberTextFieldState(state.query) }
        val keyboard = rememberKeyboardOptions()
        var lightKeys by rememberSaveable { mutableStateOf(false) }

        WheelKeys()
        LightTheme(colors = colors) {
            if (state.editorOpen) {
                if (lightKeys) {
                    LightTextInputEditor(
                        title = "Search",
                        editorKey = state.editorSession,
                        keyboardOptionsFlow = keyboard,
                        state = input,
                        onSubmit = viewModel::submit,
                        onBack = { lightKeys = false },
                        submitIcon = LightIcons.SEARCH,
                        showBackButton = true,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    SystemTextEntry(
                        title = "Search",
                        state = input,
                        submitLabel = "SEARCH",
                        hint = "Words in a title or article",
                        onSubmit = viewModel::submit,
                        onBack = { goBack() },
                        onUseLightKeys = { lightKeys = true },
                    )
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(LightThemeTokens.colors.background),
                ) {
                    LightTopBar(
                        leftButton = LightBarButton.LightIcon(LightIcons.BACK, onClick = { goBack() }),
                        center = LightTopBarCenter.Text("“${state.query}”"),
                        rightButton = LightBarButton.LightIcon(LightIcons.SEARCH, onClick = viewModel::edit),
                    )
                    ArticleList(
                        articles = results,
                        emptyMessage = "No matching articles.",
                        onOpen = { row -> navigateTo({ articleReader(it, row.article.id, repository) }) },
                        modifier = Modifier.weight(1f),
                        imageStore = imageStore,
                    )
                }
            }
        }
    }
}

class ReaderScreen(
    sealedActivity: SealedLightActivity,
    private val articleId: String,
    private val repository: RssRepository,
) : LightScreen<Unit, ReaderViewModel>(sealedActivity) {
    override val viewModelClass: Class<ReaderViewModel> = ReaderViewModel::class.java
    override fun createViewModel() = ReaderViewModel(articleId, repository)

    @Composable
    override fun Content() {
        val colors by LightThemeController.colors.collectAsState()
        val row by viewModel.article.collectAsState()
        val article = row?.article
        val imageStore = rememberImageStore(repository)
        val scroll = rememberScrollState()

        WheelKeys()
        WheelScroll(scroll)
        LightTheme(colors = colors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(LightIcons.BACK, onClick = { goBack() }),
                    center = LightTopBarCenter.Text(row?.feedTitle ?: "Article"),
                    rightButton = LightBarButton.LightIcon(
                        icon = if (article?.isStarred == true) LightIcons.STAR else LightIcons.STAR_OUTLINE,
                        onClick = viewModel::toggleStar,
                        contentDescription = if (article?.isStarred == true) {
                            "Remove from saved"
                        } else {
                            "Save article"
                        },
                    ),
                )
                if (article == null) {
                    EmptyState("Loading article…", Modifier.weight(1f))
                } else {
                    LightScrollView(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        scrollState = scroll,
                    ) {
                        Column(
                            modifier = Modifier.padding(
                                start = 1f.gridUnitsAsDp(),
                                end = 1f.gridUnitsAsDp(),
                                bottom = 2f.gridUnitsAsDp(),
                            ),
                        ) {
                            LightText(
                                text = article.title,
                                variant = LightTextVariant.Subheading,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 0.6f.gridUnitsAsDp()),
                            )
                            LightText(
                                text = listOfNotNull(
                                    article.author.takeIf { it.isNotBlank() },
                                    fullDate(article.publishedAt),
                                ).joinToString(" · "),
                                variant = LightTextVariant.Superfine,
                                lighten = true,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp()),
                            )
                            ArticleBody(
                                article = article,
                                imageStore = imageStore,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            val source = sourceHost(article.link)
                            if (source.isNotBlank()) {
                                LightText(
                                    text = source,
                                    variant = LightTextVariant.Superfine,
                                    lighten = true,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 1.25f.gridUnitsAsDp()),
                                )
                            }

                            // Actions sit at the end of the article rather than in a fixed bar, the
                            // way the reader-mode page does, so the text gets the whole screen.
                            val link = article.link
                            if (link.isNotBlank()) {
                                SettingsRow("OPEN", "Read the full page here") {
                                    navigateTo({
                                        ReaderPageScreen(it, article.id, link, article.title, repository)
                                    })
                                }
                            }
                            SettingsRow(
                                title = if (article.isRead) "MARK UNREAD" else "MARK READ",
                                detail = if (article.isRead) {
                                    "Put it back in the unread list"
                                } else {
                                    "Take it out of the unread list"
                                },
                                onClick = viewModel::toggleRead,
                            )
                            SettingsRow("ARCHIVE", "Hide it from every list") {
                                viewModel.archive { goBack() }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Reader-mode view of the page an article links to: the body copy and its images, pulled out of
 * the page and rendered with Light typography. No browser, no scripts, no page furniture.
 */
class ReaderPageScreen(
    sealedActivity: SealedLightActivity,
    private val articleId: String,
    private val link: String,
    private val fallbackTitle: String,
    private val repository: RssRepository,
) : LightScreen<Unit, ReaderPageViewModel>(sealedActivity) {
    override val viewModelClass: Class<ReaderPageViewModel> = ReaderPageViewModel::class.java
    override fun createViewModel() = ReaderPageViewModel(articleId, link, repository)

    @Composable
    override fun Content() {
        val colors by LightThemeController.colors.collectAsState()
        val state by viewModel.state.collectAsState()
        val imageStore = rememberImageStore(repository)
        val page = state.page
        val target = state.resolvedUrl.ifBlank { link }
        val scroll = rememberScrollState()

        WheelKeys()
        WheelScroll(scroll)
        LightTheme(colors = colors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(LightIcons.BACK, onClick = { goBack() }),
                    center = LightTopBarCenter.Text(sourceHost(link).ifBlank { "Article" }),
                )
                when {
                    state.isLoading -> LoadingScreen("Fetching the page\u2026", Modifier.weight(1f))

                    else -> LightScrollView(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        scrollState = scroll,
                    ) {
                        Column(
                            modifier = Modifier.padding(
                                start = 1f.gridUnitsAsDp(),
                                end = 1f.gridUnitsAsDp(),
                                bottom = 2f.gridUnitsAsDp(),
                            ),
                        ) {
                            if (page != null) {
                                LightText(
                                    text = page.title.ifBlank { fallbackTitle },
                                    variant = LightTextVariant.Subheading,
                                    modifier = Modifier.padding(top = 0.6f.gridUnitsAsDp()),
                                )
                                val credit = listOf(page.byline, sourceHost(link))
                                    .filter { it.isNotBlank() }
                                    .joinToString(" \u00b7 ")
                                if (credit.isNotBlank()) {
                                    LightText(
                                        text = credit,
                                        variant = LightTextVariant.Superfine,
                                        lighten = true,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp()),
                                    )
                                }
                                ContentBlocksBody(
                                    blocks = page.blocks,
                                    imageStore = imageStore,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            } else {
                                LightText(
                                    text = state.error ?: "That page could not be opened.",
                                    variant = LightTextVariant.Paragraph,
                                    modifier = Modifier.padding(top = 1f.gridUnitsAsDp()),
                                )
                                LightText(
                                    text = target,
                                    variant = LightTextVariant.Superfine,
                                    lighten = true,
                                    modifier = Modifier.padding(top = 1f.gridUnitsAsDp()),
                                )
                            }

                            // Actions sit at the end of the article rather than in a fixed bar.
                            SettingsRow(
                                title = "SIGN IN",
                                detail = "Open this page here and keep the sign-in",
                            ) {
                                navigateTo({ SignInScreen(it, target, repository) }) {
                                    viewModel.retry()
                                }
                            }
                            SettingsRow("RELOAD", "Fetch the page again") { viewModel.retry() }
                        }
                    }
                }
            }
        }
    }
}

class SettingsScreen(
    sealedActivity: SealedLightActivity,
    private val repository: RssRepository,
) : LightScreen<Unit, SettingsViewModel>(sealedActivity) {
    override val viewModelClass: Class<SettingsViewModel> = SettingsViewModel::class.java
    override fun createViewModel() = SettingsViewModel(repository)

    @Composable
    override fun Content() {
        val colors by LightThemeController.colors.collectAsState()
        val imagesEnabled by viewModel.imagesEnabled.collectAsState()
        val scroll = rememberScrollState()

        WheelKeys()
        WheelScroll(scroll)
        LightTheme(colors = colors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(LightIcons.BACK, onClick = { goBack() }),
                    center = LightTopBarCenter.Text("Settings"),
                )
                LightScrollView(modifier = Modifier.weight(1f), scrollState = scroll) {
                    SettingsRow("APPEARANCE", "Toggle light / dark") {
                        LightThemeController.toggle()
                    }
                    SettingsRow("SUBSCRIPTIONS", "Feeds you follow") {
                        navigateTo({ FeedsScreen(it, repository) })
                    }
                    SettingsRow("MAILBOX", "Gmail account, labels and newsletter rendering") {
                        navigateTo({ MailboxScreen(it, repository) })
                    }
                    SettingsRow(
                        title = if (imagesEnabled) "IMAGES ON" else "IMAGES OFF",
                        detail = if (imagesEnabled) {
                            "Feed images load as you read"
                        } else {
                            "Text only, no image downloads"
                        },
                    ) {
                        viewModel.setImagesEnabled(!imagesEnabled)
                    }
                    SettingsRow("CLEAR IMAGE CACHE", "Remove downloaded images, keep article text") {
                        viewModel.clearImages()
                    }
                    SettingsRow("MARK ALL READ", "Keep saved articles and history") {
                        navigateTo({
                            ConfirmationScreen(
                                it,
                                message = "Mark every article as read?\n\nSaved items and history will stay.",
                                confirmLabel = "MARK READ",
                            )
                        }) { confirmed ->
                            if (confirmed) viewModel.markAllRead()
                        }
                    }
                    SettingsRow("CLEAR READ ARTICLES", "Remove local copies except saved items") {
                        navigateTo({
                            ConfirmationScreen(
                                it,
                                message = "Clear every read article that is not saved?\n\nSubscriptions and starred articles will stay.",
                                confirmLabel = "CLEAR",
                            )
                        }) { confirmed ->
                            if (confirmed) viewModel.clearRead()
                        }
                    }
                    Column(modifier = Modifier.padding(1f.gridUnitsAsDp())) {
                        LightText("ABOUT", LightTextVariant.Superfine, lighten = true)
                        LightText(
                            text = "Offline RSS, Atom and Gmail newsletters in one list. No app " +
                                "ads. The RSS reader uses no WebView at all; newsletters use one, " +
                                "with scripts off.",
                            variant = LightTextVariant.Detail,
                            modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp()),
                        )
                        LightText(
                            text = "QR CODE GENERATOR",
                            variant = LightTextVariant.Superfine,
                            lighten = true,
                            modifier = Modifier.padding(top = 1f.gridUnitsAsDp()),
                        )
                        LightText(
                            text = QR_GENERATOR_URL,
                            variant = LightTextVariant.Detail,
                            modifier = Modifier.padding(top = 0.25f.gridUnitsAsDp()),
                        )
                        LightText(
                            text = "VERSION 2.2.0",
                            variant = LightTextVariant.Superfine,
                            lighten = true,
                            modifier = Modifier.padding(top = 1f.gridUnitsAsDp()),
                        )
                    }
                }
            }
        }
    }
}

internal class ConfirmationScreen(
    sealedActivity: SealedLightActivity,
    private val message: String,
    private val confirmLabel: String,
) : SimpleLightScreen<Boolean>(sealedActivity) {
    @Composable
    override fun Content() {
        val colors by LightThemeController.colors.collectAsState()
        LightTheme(colors = colors) {
            ConfirmationContent(
                message = message,
                confirmLabel = confirmLabel,
                onCancel = { goBack(false) },
                onConfirm = { goBack(true) },
            )
        }
    }
}

internal class MessageScreen(
    sealedActivity: SealedLightActivity,
    private val message: String,
) : SimpleLightScreen<Unit>(sealedActivity) {
    @Composable
    override fun Content() {
        val colors by LightThemeController.colors.collectAsState()
        LightTheme(colors = colors) {
            LightFullscreenModal(message = message, onClose = { goBack() })
        }
    }
}

@Composable
internal fun SettingsRow(title: String, detail: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClickLabel = title, role = Role.Button) { onClick() }
            .padding(horizontal = 1f.gridUnitsAsDp(), vertical = 0.75f.gridUnitsAsDp()),
    ) {
        LightText(title, LightTextVariant.Paragraph)
        LightText(
            detail,
            LightTextVariant.Superfine,
            lighten = true,
            modifier = Modifier.padding(top = 0.25f.gridUnitsAsDp()),
        )
    }
}

@Composable
internal fun LoadingScreen(message: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(LightThemeTokens.colors.background),
        contentAlignment = Alignment.Center,
    ) {
        LightText(message, LightTextVariant.Paragraph, lighten = true)
    }
}

/** Where to generate a scannable feed code. Shown as text: the reader never opens a browser. */
private const val QR_GENERATOR_URL = "gi-os.github.io/LightRSS"
