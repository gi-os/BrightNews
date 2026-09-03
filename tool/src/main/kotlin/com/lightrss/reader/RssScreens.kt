package com.lightrss.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import java.util.Locale
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.Animatable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.painterResource
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
            RssDatabase.MIGRATION_4_5,
            RssDatabase.MIGRATION_5_6,
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
        val section by viewModel.section.collectAsState()
        val unreadOnly by viewModel.unreadOnly.collectAsState()
        val favoritesOnly by viewModel.favoritesOnly.collectAsState()
        val favoriteCount by viewModel.favoriteFeedCount.collectAsState()
        val feedCount by viewModel.feedCount.collectAsState()
        val labelCount by viewModel.labelCount.collectAsState()
        val edition by viewModel.edition.collectAsState()
        val expanded by viewModel.expanded.collectAsState()
        val today by viewModel.today.collectAsState()
        val todayTick by viewModel.todayTick.collectAsState()
        val timeline by viewModel.timeline.collectAsState()
        val timelineUnread by viewModel.timelineUnread.collectAsState()
        val sync by viewModel.syncState.collectAsState()
        val jumpToNewest by viewModel.jumpToNewest.collectAsState()
        val repository = viewModel.repository
        val imageStore = rememberImageStore(repository)
        val listState = rememberLazyListState()
        val briefingScroll = rememberScrollState()
        val chrome = rememberChromeVisibility()
        val briefing = section == HomeSection.BRIEFING
        val context = LocalContext.current

        // Today comes from the notebook, read here because the provider needs a Context and
        // the view model has none. Re-read on every show and every refresh: the calendar
        // changes while you are in the other app.
        LaunchedEffect(todayTick) {
            val day = withContext(Dispatchers.IO) { runCatching { NotebookBridge.read(context) }.getOrNull() }
            viewModel.setToday(day)
        }

        LaunchedEffect(jumpToNewest, briefing) {
            if (jumpToNewest > 0) {
                if (briefing) briefingScroll.scrollTo(0) else listState.scrollToItem(0)
            }
        }

        if (briefing) {
            ChromeScrollEffect(briefingScroll, chrome)
        } else {
            ChromeScrollEffect(listState, chrome, ROW_STEP_PX)
        }

        WheelKeys()
        LightTheme(colors = colors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                ReaderChrome(chrome.visible) {
                    key(briefing) {
                        LightTopBar(
                            leftButton = LightBarButton.LightIcon(
                                icon = LightIcons.LIST,
                                onClick = {
                                    if (briefing) {
                                        navigateTo({ KagiScreen(it, repository) })
                                    } else {
                                        navigateTo({ FeedsScreen(it, repository) })
                                    }
                                },
                                contentDescription = if (briefing) "Kagi categories" else "Subscriptions",
                            ),
                            center = LightTopBarCenter.Text(if (briefing) "Daily Briefing" else "Timeline"),
                            rightButton = LightBarButton.LightIcon(
                                icon = LightIcons.SEARCH,
                                onClick = { navigateTo({ SearchScreen(it, repository) }) },
                                contentDescription = "Search everything",
                            ),
                        )
                    }
                }
                StatusLine(
                    when {
                        sync.isRefreshing -> "SYNC ${sync.completedFeeds}/${sync.totalFeeds}"
                        sync.message?.contains("could not", ignoreCase = true) == true -> sync.message
                        !briefing && timelineUnread > 0 -> "$timelineUnread UNREAD"
                        else -> null
                    },
                )
                if (briefing) {
                    BriefingContent(
                        today = today,
                        edition = edition,
                        expanded = expanded,
                        onToggle = viewModel::toggleExpanded,
                        onOpen = { row -> navigateTo({ articleReader(it, row.article.id, repository) }) },
                        scroll = briefingScroll,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    TimelineList(
                        items = timeline,
                        emptyMessage = emptyMessage(unreadOnly, favoritesOnly, favoriteCount, feedCount, labelCount),
                        onOpen = { row -> navigateTo({ articleReader(it, row.article.id, repository) }) },
                        modifier = Modifier.weight(1f),
                        imageStore = imageStore,
                        listState = listState,
                    )
                }
                ReaderChrome(chrome.visible) {
                    LightBottomBar(
                        items = listOf(
                            SectionTab(
                                iconRes = R.drawable.ic_kagi_white,
                                label = "Daily Briefing",
                                selected = briefing,
                                onClick = { viewModel.showSection(HomeSection.BRIEFING) },
                            ),
                            SectionTab(
                                iconRes = R.drawable.ic_rss_white,
                                label = "Timeline",
                                selected = !briefing,
                                onClick = { viewModel.showSection(HomeSection.TIMELINE) },
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

    private fun emptyMessage(
        unreadOnly: Boolean,
        favoritesOnly: Boolean,
        favoriteCount: Int,
        feedCount: Int,
        labelCount: Int,
    ): String = when {
        feedCount == 0 && labelCount == 0 ->
            "Nothing to read yet.\n\nOpen the list button to add a feed or connect Gmail."
        favoritesOnly && favoriteCount == 0 ->
            "No favorite feeds yet.\n\nStar a feed in Subscriptions, or switch back to all feeds."
        unreadOnly -> "You’re all caught up.\n\nSwitch the filter to see everything again."
        else -> "No articles yet.\n\nRefresh or add a subscription."
    }
}

/**
 * One of the two tabs, in the bottom bar.
 *
 * The selected one is filled and the other is outlined — the panel has no accent colour to spend
 * on a selection, and dimming the inactive tab would read as disabled rather than unselected.
 *
 * A painter rather than a [LightIcons] entry because the SDK has neither an RSS mark nor an open
 * envelope. The two drawables live in `tool/src/main/res/drawable` and are drawn to the same
 * spec as the SDK's own — white on transparent, 24dp, 2dp round-cap strokes — so they do not read
 * as visitors in the bar.
 */
@Composable
private fun SectionTab(
    iconRes: Int,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
): LightBarButton = LightBarButton.Icon(
    painter = painterResource(iconRes),
    onClick = onClick,
    contentDescription = if (selected) "$label, showing" else label,
    sizeUnits = if (selected) 2.3f else 2f,
)

/**
 * A stand-in for a row's height in pixels.
 *
 * The list reports its position as an item index plus an offset, and turning that into a real
 * distance would mean measuring rows. For deciding whether a scroll was a deliberate move, a
 * constant per row is enough — it only has to be the right order of magnitude.
 */
internal const val ROW_STEP_PX = 260

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
        val unreadOnly by viewModel.unreadOnly.collectAsState()

        val chrome = rememberChromeVisibility()
        val listState = rememberLazyListState()
        ChromeScrollEffect(listState, chrome, ROW_STEP_PX)
        WheelKeys()
        LightTheme(colors = colors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                ReaderChrome(chrome.visible) {
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
                }
                SettingsRow(
                    title = if (unreadOnly) "SHOWING UNREAD" else "SHOWING ALL",
                    detail = if (unreadOnly) {
                        "Read items are hidden — tap to show everything"
                    } else {
                        "Everything, read or not — tap for unread only"
                    },
                    onClick = viewModel::toggleUnreadOnly,
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
                    listState = listState,
                )
                ReaderChrome(chrome.visible) {
                    LightBottomBar(
                        items = listOf(
                            // The timeline is RSS and newsletters together, so its sources
                            // screen has to reach both: the mailbox sits beside saved and archive.
                            LightBarButton.Icon(
                                painter = painterResource(R.drawable.ic_newsletter_white),
                                onClick = { navigateTo({ MailboxScreen(it, repository) }) },
                                contentDescription = "Mailbox",
                            ),
                            LightBarButton.LightIcon(
                                icon = LightIcons.STAR_OUTLINE,
                                onClick = { navigateTo({ SavedScreen(it, repository) }) },
                                contentDescription = "Saved articles",
                            ),
                            LightBarButton.LightIcon(
                                icon = LightIcons.DELETE,
                                onClick = { navigateTo({ ArchiveScreen(it, repository) }) },
                                contentDescription = "Archive",
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
                        start = SIDE_MARGIN_UNITS.gridUnitsAsDp(),
                        end = SIDE_MARGIN_UNITS.gridUnitsAsDp(),
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
        val nextKagi by viewModel.nextKagi.collectAsState()
        val imageStore = rememberImageStore(repository)
        val isKagi = feed?.sourceType == Source.KAGI

        val chrome = rememberChromeVisibility()
        val listState = rememberLazyListState()
        ChromeScrollEffect(listState, chrome, ROW_STEP_PX)
        WheelKeys()
        LightTheme(colors = colors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                ReaderChrome(chrome.visible) {
                    LightTopBar(
                        leftButton = LightBarButton.LightIcon(LightIcons.BACK, onClick = { goBack() }),
                        center = LightTopBarCenter.Text(feed?.title ?: "Feed"),
                        rightButton = LightBarButton.LightIcon(LightIcons.REFRESH, onClick = viewModel::refresh),
                    )
                }
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
                    listState = listState,
                    imageStore = imageStore,
                    // Kagi reads category by category; turning off the end of one is the
                    // same request as the NEXT button.
                    onEdge = { direction ->
                        val target = nextKagi
                        if (direction > 0 && isKagi && target != null) {
                            navigateTo({ FeedScreen(it, target.id, repository) })
                        }
                    },
                )
                val unfollow = LightBarButton.LightIcon(
                    LightIcons.DELETE,
                    onClick = {
                        val title = feed?.title ?: "this feed"
                        navigateTo({ DeleteFeedScreen(it, feedId, title, repository) }) {
                            goBack()
                        }
                    },
                )
                ReaderChrome(chrome.visible) {
                    LightBottomBar(
                        items = if (isKagi) {
                            // Favourites narrow the RSS home list; a Kagi category is read on its own.
                            // NEXT turns the page to the following category, so a morning's reading
                            // is one tap per category rather than a trip back to the list each time.
                            listOf(
                                LightBarButton.Text("READ ALL", onClick = viewModel::markAllRead),
                                nextKagi?.let { next ->
                                    LightBarButton.Text("NEXT", onClick = {
                                        navigateTo({ FeedScreen(it, next.id, repository) })
                                    })
                                },
                                unfollow,
                            )
                        } else {
                            listOf(
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
                                unfollow,
                            )
                        },
                    )
                }
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
        val chrome = rememberChromeVisibility()
        val listState = rememberLazyListState()
        ChromeScrollEffect(listState, chrome, ROW_STEP_PX)
        WheelKeys()
        LightTheme(colors = colors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                ReaderChrome(chrome.visible) {
                    LightTopBar(
                        leftButton = LightBarButton.LightIcon(LightIcons.BACK, onClick = { goBack() }),
                        center = LightTopBarCenter.Text("Saved"),
                    )
                }
                ArticleList(
                    articles = articles,
                    emptyMessage = "No saved articles.\n\nUse the star while reading to keep something.",
                    onOpen = { row -> navigateTo({ articleReader(it, row.article.id, repository) }) },
                    modifier = Modifier.weight(1f),
                    listState = listState,
                    imageStore = imageStore,
                )
            }
        }
    }
}

/**
 * The archive.
 *
 * Archiving hides an article from every list, and for as long as no screen could see a hidden
 * row that was indistinguishable from deleting it — a mis-tap took the article for good. This is
 * that screen: everything hidden, newest first, opened in the same reader as anything else.
 * Restoring one is a row in the reader; restoring the lot is the button in the bar, which is what
 * an accidental archive actually needs.
 */
class ArchiveScreen(
    sealedActivity: SealedLightActivity,
    private val repository: RssRepository,
) : LightScreen<Unit, ArchiveViewModel>(sealedActivity) {
    override val viewModelClass: Class<ArchiveViewModel> = ArchiveViewModel::class.java
    override fun createViewModel() = ArchiveViewModel(repository)

    @Composable
    override fun Content() {
        val colors by LightThemeController.colors.collectAsState()
        val articles by viewModel.articles.collectAsState()
        val imageStore = rememberImageStore(repository)
        val chrome = rememberChromeVisibility()
        val listState = rememberLazyListState()
        ChromeScrollEffect(listState, chrome, ROW_STEP_PX)
        WheelKeys()
        LightTheme(colors = colors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                ReaderChrome(chrome.visible) {
                    LightTopBar(
                        leftButton = LightBarButton.LightIcon(LightIcons.BACK, onClick = { goBack() }),
                        center = LightTopBarCenter.Text("Archive"),
                    )
                }
                ArticleList(
                    articles = articles,
                    emptyMessage = "Nothing archived.\n\nArchiving hides an article from your " +
                        "lists and keeps it here.",
                    onOpen = { row -> navigateTo({ articleReader(it, row.article.id, repository) }) },
                    modifier = Modifier.weight(1f),
                    listState = listState,
                    imageStore = imageStore,
                )
                if (articles.isNotEmpty()) {
                    ReaderChrome(chrome.visible) {
                        LightBottomBar(
                            items = listOf(
                                LightBarButton.Text(
                                    text = "RESTORE ALL",
                                    onClick = viewModel::restoreAll,
                                ),
                            ),
                        )
                    }
                }
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
        val fetchingFullText by viewModel.fetchingFullText.collectAsState()
        val next by viewModel.next.collectAsState()
        val previous by viewModel.previous.collectAsState()
        val position by viewModel.position.collectAsState()
        val turned by viewModel.turned.collectAsState()
        val article = row?.article
        val imageStore = rememberImageStore(repository)
        val colour by repository.colourEnabled.collectAsState(initial = true)
        val scroll = rememberScrollState()
        val isKagi = article?.guid?.startsWith("kagi:") == true

        // Turning the page slides the article out the way you were going and the next one in
        // from the other side; the scroll resets between the two frames so the new article
        // starts at the top under the slide.
        val slide = remember { Animatable(0f) }
        val slideScope = rememberCoroutineScope()
        var turning by remember { mutableStateOf(false) }
        val viewportPx = with(LocalDensity.current) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
        fun turnWithSlide(direction: Int) {
            if (turning) return
            val target = if (direction > 0) next else previous
            if (target == null) return
            turning = true
            slideScope.launch {
                slide.animateTo(-direction * viewportPx, tween(SLIDE_OUT_MS, easing = FastOutLinearInEasing))
                viewModel.turn(direction)
                scroll.scrollTo(0)
                slide.snapTo(direction * viewportPx)
                slide.animateTo(0f, tween(SLIDE_IN_MS, easing = LinearOutSlowInEasing))
                turning = false
            }
        }

        // Pulling up past the end of the article, the way a webtoon reader pulls the next
        // episode in: the landing zone at the bottom is the invitation, the overscroll is the
        // answer. A finger has to travel a real distance so a bounce at the end does nothing.
        val pull = remember { Pull() }
        val pullConnection = remember(scroll) {
            object : NestedScrollConnection {
                override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                    if (source != NestedScrollSource.UserInput) return Offset.Zero
                    if (available.y < 0f && !scroll.canScrollForward) {
                        pull.px += -available.y
                        if (pull.px > PULL_TO_TURN_PX && !turning && next != null) {
                            pull.px = 0f
                            turnWithSlide(1)
                        }
                    } else if (available.y > 0f && !scroll.canScrollBackward) {
                        pull.px += -available.y
                        if (pull.px < -PULL_TO_TURN_PX && !turning && previous != null) {
                            pull.px = 0f
                            turnWithSlide(-1)
                        }
                    } else {
                        pull.px = 0f
                    }
                    return Offset.Zero
                }

                override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                    pull.px = 0f
                    return Velocity.Zero
                }
            }
        }

        // Only while there is actually something to see in colour. An article the feed gave no
        // picture for has nothing but white-on-black type on screen, and lifting the whole
        // phone's greyscale for that would be a change with no visible cause.
        ColourEffect(colour && imageStore != null && article?.let { it.imageUrl.isNotBlank() || it.contentBlocks.isNotBlank() } == true)

        val chrome = rememberChromeVisibility()
        ChromeScrollEffect(scroll, chrome)
        WheelKeys()
        // Off the bottom and keep turning: the next article in this feed, here, in place. Off
        // the top: the previous one. One notch past the end is enough now that the landing
        // zone under the article says what the turn will open.
        val wheelEdge = WheelScroll(scroll, onEdge = { direction -> turnWithSlide(direction) }, edgeNotches = 1)
        LightTheme(colors = colors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                ReaderChrome(chrome.visible) {
                    LightTopBar(
                        leftButton = LightBarButton.LightIcon(LightIcons.BACK, onClick = { goBack() }),
                        center = LightTopBarCenter.Text(
                            // A Kagi story says where it sits in its category: "World · 1 of 12".
                            position?.let { (at, of) -> "${row?.feedTitle} · $at of $of" }
                                ?: row?.feedTitle
                                ?: "Article",
                        ),
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
                }
                StatusLine(
                    when {
                        fetchingFullText -> "FETCHING THE WHOLE ARTICLE…"
                        wheelEdge.edge > 0 && next != null -> "TURN AGAIN FOR: ${next?.title}"
                        wheelEdge.edge < 0 && previous != null -> "TURN AGAIN FOR: ${previous?.title}"
                        wheelEdge.edge > 0 -> "END OF THIS FEED"
                        else -> null
                    },
                )
                if (article == null) {
                    EmptyState("Loading article…", Modifier.weight(1f))
                } else {
                    LightScrollView(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .nestedScroll(pullConnection)
                            .graphicsLayer { translationY = slide.value },
                        scrollState = scroll,
                    ) {
                        Column(
                            modifier = Modifier.padding(
                                start = READER_MARGIN_UNITS.gridUnitsAsDp(),
                                end = READER_MARGIN_UNITS.gridUnitsAsDp(),
                                bottom = 3f.gridUnitsAsDp(),
                            ),
                        ) {
                            LightText(
                                text = article.title,
                                variant = LightTextVariant.Heading,
                                maxLines = 6,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 1f.gridUnitsAsDp()),
                            )
                            // One byline. The bar already names the feed, and the source list
                            // or the OPEN row already names the site, so this is the author
                            // and the date and nothing else.
                            LightText(
                                text = listOfNotNull(
                                    if (isKagi) {
                                        article.sourceCount.takeIf { it > 0 }?.let { "$it SOURCES" }
                                    } else {
                                        article.author.takeIf { it.isNotBlank() }
                                    },
                                    fullDate(article.publishedAt),
                                ).joinToString(" · "),
                                variant = LightTextVariant.Fine,
                                lighten = true,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 0.75f.gridUnitsAsDp()),
                            )
                            ReaderType {
                                ArticleBody(
                                    article = article,
                                    imageStore = imageStore,
                                    modifier = Modifier.fillMaxWidth().padding(top = 0.5f.gridUnitsAsDp()),
                                    onLink = { link ->
                                        navigateTo({
                                            ReaderPageScreen(it, article.id + ":" + link.url.hashCode(), link.url, link.text, repository)
                                        })
                                    },
                                )
                            }

                            // Actions sit at the end of the article rather than in a fixed bar,
                            // so the text gets the whole screen — but as one line of buttons,
                            // not three more rows that look like more article.
                            val link = article.link
                            ArticleActions(
                                actions = buildList {
                                    if (link.isNotBlank() && !isKagi) {
                                        add("OPEN" to {
                                            navigateTo({
                                                ReaderPageScreen(it, article.id, link, article.title, repository)
                                            })
                                        })
                                    }
                                    add((if (article.isRead) "UNREAD" else "READ") to viewModel::toggleRead)
                                    add((if (article.isArchived) "RESTORE" else "ARCHIVE") to { viewModel.toggleArchived { goBack() } })
                                },
                            )

                            // The landing zone. Past the actions the page keeps going into
                            // what comes next — its title and a line of it — so the end of an
                            // article is a doorway, not a wall. Pulling up here, or one more
                            // notch of the wheel, turns the page.
                            UpNext(
                                next = next,
                                feedTitle = row?.feedTitle.orEmpty(),
                                minHeightPx = viewportPx * UP_NEXT_FRACTION,
                                onOpen = { turnWithSlide(1) },
                            )
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
        val colour by repository.colourEnabled.collectAsState(initial = true)
        val page = state.page
        val target = state.resolvedUrl.ifBlank { link }
        val scroll = rememberScrollState()

        ColourEffect(colour && imageStore != null && page?.blocks?.isNotEmpty() == true)

        val chrome = rememberChromeVisibility()
        ChromeScrollEffect(scroll, chrome)
        WheelKeys()
        WheelScroll(scroll)
        LightTheme(colors = colors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                ReaderChrome(chrome.visible) {
                    LightTopBar(
                        leftButton = LightBarButton.LightIcon(LightIcons.BACK, onClick = { goBack() }),
                        center = LightTopBarCenter.Text(sourceHost(link).ifBlank { "Article" }),
                    )
                }
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
                                start = READER_MARGIN_UNITS.gridUnitsAsDp(),
                                end = READER_MARGIN_UNITS.gridUnitsAsDp(),
                                bottom = 3f.gridUnitsAsDp(),
                            ),
                        ) {
                            if (page != null) {
                                LightText(
                                    text = page.title.ifBlank { fallbackTitle },
                                    variant = LightTextVariant.Heading,
                                    modifier = Modifier.padding(top = 1f.gridUnitsAsDp()),
                                )
                                val credit = listOf(page.byline, sourceHost(link))
                                    .filter { it.isNotBlank() }
                                    .joinToString(" \u00b7 ")
                                if (credit.isNotBlank()) {
                                    LightText(
                                        text = credit,
                                        variant = LightTextVariant.Fine,
                                        lighten = true,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(top = 0.75f.gridUnitsAsDp()),
                                    )
                                }
                                ReaderType {
                                    ContentBlocksBody(
                                        blocks = page.blocks,
                                        imageStore = imageStore,
                                        modifier = Modifier.fillMaxWidth().padding(top = 0.5f.gridUnitsAsDp()),
                                    )
                                }
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
        val fullTextEnabled by viewModel.fullTextEnabled.collectAsState()
        val colourEnabled by viewModel.colourEnabled.collectAsState()
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
                        title = if (fullTextEnabled) "FULL ARTICLES ON" else "FULL ARTICLES OFF",
                        detail = if (fullTextEnabled) {
                            "The whole story is fetched behind a feed's summary"
                        } else {
                            "Only what the feed sends; OPEN fetches the page"
                        },
                    ) {
                        viewModel.setFullTextEnabled(!fullTextEnabled)
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
                    SettingsRow(
                        title = if (colourEnabled) "COLOUR ON" else "COLOUR OFF",
                        detail = if (colourEnabled) {
                            "Pictures show in colour, the rest of the phone stays grey"
                        } else {
                            "Everything stays grey, like the rest of LightOS"
                        },
                    ) {
                        viewModel.setColourEnabled(!colourEnabled)
                    }
                    SettingsRow("MARK ALL READ", "Leaves saved and archived articles alone") {
                        navigateTo({
                            ConfirmationScreen(
                                it,
                                message = "Mark every article as read?\n\nSaved and archived articles are not touched.",
                                confirmLabel = "MARK READ",
                            )
                        }) { confirmed ->
                            if (confirmed) viewModel.markAllRead()
                        }
                    }
                    SettingsRow("CLEAR READ ARTICLES", "Removes local copies, keeps saved and archived") {
                        navigateTo({
                            ConfirmationScreen(
                                it,
                                message = "Clear every read article that is not saved?\n\n" +
                                    "Subscriptions stay. Saved and archived articles stay, and so " +
                                    "does anything still waiting to be marked read in Gmail.",
                                confirmLabel = "CLEAR",
                            )
                        }) { confirmed ->
                            if (confirmed) viewModel.clearRead()
                        }
                    }
                    Column(modifier = Modifier.padding(SIDE_MARGIN_UNITS.gridUnitsAsDp())) {
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
                            // Generated from lighttool.toml, so it cannot fall behind a release
                            // again the way the hand-written string did.
                            text = "VERSION ${BuildConfig.VERSION_NAME}",
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
            .lightClickable(onClickLabel = title, role = Role.Button) { onClick() },
    ) {
        Column(
            modifier = Modifier.padding(
                horizontal = SIDE_MARGIN_UNITS.gridUnitsAsDp(),
                vertical = ROW_PADDING_UNITS.gridUnitsAsDp(),
            ),
        ) {
            LightText(title, LightTextVariant.Paragraph)
            LightText(
                detail,
                LightTextVariant.Fine,
                lighten = true,
                modifier = Modifier.padding(top = 0.35f.gridUnitsAsDp()),
            )
        }
        HairlineDivider()
    }
}

/**
 * The end-of-article controls: up to three labels on one line under a rule, spaced across the
 * reading width. Set in Detail rather than the SDK's Button type because three 30sp words do not
 * fit across this panel side by side.
 */
@Composable
internal fun ArticleActions(actions: List<Pair<String, () -> Unit>>) {
    Column(modifier = Modifier.padding(top = SECTION_GAP_UNITS.gridUnitsAsDp())) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(LightThemeTokens.colors.contentSecondary.copy(alpha = 0.35f)),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            actions.forEach { (label, onClick) ->
                LightText(
                    text = label,
                    variant = LightTextVariant.Detail,
                    maxLines = 1,
                    modifier = Modifier
                        .lightClickable(onClickLabel = label, role = Role.Button) { onClick() }
                        .padding(vertical = 1f.gridUnitsAsDp(), horizontal = 0.25f.gridUnitsAsDp()),
                )
            }
        }
    }
}

/**
 * What comes after this article, set at the foot of it so that scrolling on carries you into the
 * next one. Sized to a good part of the screen: the point is that the reader arrives here with
 * nothing else in view and reads the next title before deciding.
 */
@Composable
internal fun UpNext(next: ArticleEntity?, feedTitle: String, minHeightPx: Float, onOpen: () -> Unit) {
    val minHeight = with(LocalDensity.current) { minHeightPx.toDp() }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .padding(top = 3f.gridUnitsAsDp()),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(LightThemeTokens.colors.contentSecondary.copy(alpha = 0.35f)),
        )
        if (next == null) {
            LightText(
                text = "END OF ${feedTitle.uppercase(Locale.US)}",
                variant = LightTextVariant.Fine,
                lighten = true,
                modifier = Modifier.padding(top = 0.6f.gridUnitsAsDp()),
            )
            LightText(
                text = "Nothing more here. Go back for the list.",
                variant = LightTextVariant.Detail,
                lighten = true,
                modifier = Modifier.padding(top = 0.6f.gridUnitsAsDp()),
            )
            return@Column
        }
        LightText(
            text = "UP NEXT",
            variant = LightTextVariant.Fine,
            lighten = true,
            modifier = Modifier.padding(top = 0.6f.gridUnitsAsDp()),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .lightClickable(onClickLabel = "Open ${next.title}", role = Role.Button) { onOpen() }
                .padding(top = 1f.gridUnitsAsDp()),
        ) {
            LightText(
                text = next.title,
                variant = LightTextVariant.Subheading,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
            val teaser = next.summary.ifBlank { next.content }.let { ContentBlocks.decode(next.contentBlocks).firstTextOr(it) }
            if (teaser.isNotBlank()) {
                LightText(
                    text = teaser,
                    variant = LightTextVariant.Detail,
                    lighten = true,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 0.6f.gridUnitsAsDp()),
                )
            }
            LightText(
                text = "Keep scrolling",
                variant = LightTextVariant.Superfine,
                lighten = true,
                modifier = Modifier.padding(top = 1.25f.gridUnitsAsDp()),
            )
        }
    }
}

private fun List<ContentBlock>.firstTextOr(fallback: String): String =
    filterIsInstance<ContentBlock.Text>().firstOrNull()?.text ?: fallback

/** Overscroll collected past the end of the article, in pixels; not Compose state on purpose. */
private class Pull {
    var px: Float = 0f
}

private const val SLIDE_OUT_MS = 160
private const val SLIDE_IN_MS = 220
/** How far a finger pulls past the end before the page turns. */
private const val PULL_TO_TURN_PX = 220f
/** The landing zone's share of the screen. */
private const val UP_NEXT_FRACTION = 0.55f

@Composable
internal fun LoadingScreen(message: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(LightThemeTokens.colors.background),
    ) {
        LightText(
            text = message,
            variant = LightTextVariant.Paragraph,
            lighten = true,
            modifier = Modifier.padding(
                start = READER_MARGIN_UNITS.gridUnitsAsDp(),
                end = READER_MARGIN_UNITS.gridUnitsAsDp(),
                top = 4f.gridUnitsAsDp(),
            ),
        )
    }
}

/** Where to generate a scannable feed code. Shown as text: the reader never opens a browser. */
private const val QR_GENERATOR_URL = "gi-os.github.io/LightRSS"
