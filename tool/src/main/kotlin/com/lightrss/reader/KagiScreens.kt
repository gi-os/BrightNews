package com.lightrss.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import com.lightrss.reader.hw.WheelKeys
import com.lightrss.reader.hw.WheelScroll
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import java.util.Locale

/**
 * The Kagi categories being followed — the Kagi tab's counterpart to Subscriptions and the
 * Mailbox. Rows open the category; `+` opens the picker.
 */
class KagiScreen(
    sealedActivity: SealedLightActivity,
    private val repository: RssRepository,
) : LightScreen<Unit, KagiFeedsViewModel>(sealedActivity) {
    override val viewModelClass: Class<KagiFeedsViewModel> = KagiFeedsViewModel::class.java
    override fun createViewModel() = KagiFeedsViewModel(repository)

    @Composable
    override fun Content() {
        val colors by LightThemeController.colors.collectAsState()
        val feeds by viewModel.feeds.collectAsState()

        WheelKeys()
        LightTheme(colors = colors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(LightIcons.BACK, onClick = { goBack() }),
                    center = LightTopBarCenter.Text("Kagi News"),
                    rightButton = LightBarButton.LightIcon(
                        LightIcons.ADD,
                        onClick = {
                            navigateTo({ KagiPickerScreen(it, repository) }) { feedId ->
                                navigateTo({ FeedScreen(it, feedId, repository) })
                            }
                        },
                        contentDescription = "Follow a category",
                    ),
                )
                if (feeds.isEmpty()) {
                    EmptyState(
                        "Kagi News is a dozen stories a day per category, each one drawn from " +
                            "dozens of sources.\n\nTap + to follow World, Technology, your city…",
                        Modifier.weight(1f),
                    )
                } else {
                    FeedList(
                        feeds = feeds,
                        onOpen = { row -> navigateTo({ FeedScreen(it, row.feed.id, repository) }) },
                        modifier = Modifier.weight(1f),
                    )
                }
                LightBottomBar(
                    items = listOf(
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

/** One line of the picker, flattened from the shelves for a lazy list. */
private sealed interface PickerLine {
    val key: String

    data class Header(val title: String) : PickerLine {
        override val key: String get() = "h:$title"
    }

    /** A parent with children folded under it: `USA`, `Germany`, `Countries`. */
    data class Parent(val title: String, val count: Int, val expanded: Boolean) : PickerLine {
        override val key: String get() = "p:$title"
    }

    data class Item(val shelved: Kagi.Shelved, val indented: Boolean) : PickerLine {
        override val key: String get() = "c:" + shelved.category.file
    }
}

/**
 * Kagi's categories, shelved.
 *
 * Around 190 categories is too many for a wheel to scroll flat, and the names already say how
 * to fold them: the handful of general categories first, then places — `USA | New York City`
 * under USA, `Germany | Berlin` under Germany, and the bare countries under one row — then the
 * topics in Kagi's alphabetical order. Parents open on a tap; nothing is more than two taps deep.
 * Returns the new feed's id, the way the RSS chooser does.
 */
class KagiPickerScreen(
    sealedActivity: SealedLightActivity,
    private val repository: RssRepository,
) : LightScreen<Long, KagiPickerViewModel>(sealedActivity) {
    override val viewModelClass: Class<KagiPickerViewModel> = KagiPickerViewModel::class.java
    override fun createViewModel() = KagiPickerViewModel(repository)

    @Composable
    override fun Content() {
        val colors by LightThemeController.colors.collectAsState()
        val state by viewModel.state.collectAsState()
        val scroll = rememberScrollState()

        LaunchedEffect(state.error) {
            val message = state.error ?: return@LaunchedEffect
            viewModel.clearError()
            navigateTo({ MessageScreen(it, message) })
        }

        WheelKeys()
        LightTheme(colors = colors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(LightIcons.BACK, onClick = { goBack() }),
                    center = LightTopBarCenter.Text("Follow a category"),
                )
                StatusLine(
                    when {
                        state.adding != null -> "FETCHING TODAY'S EDITION…"
                        else -> null
                    },
                )
                when {
                    state.isLoading -> LoadingScreen("Fetching the category list…", Modifier.weight(1f))
                    state.shelved.isEmpty() -> EmptyState(
                        "Kagi's category list could not be loaded.\n\nCheck the connection and try again.",
                        Modifier.weight(1f),
                    )
                    else -> {
                        // A plain column: rows are three different heights, so the lazy list's
                        // uniform-row scroll bar does not fit, and a hundred-odd rows is nothing.
                        WheelScroll(scroll)
                        LightScrollView(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            scrollState = scroll,
                        ) {
                            Column(modifier = Modifier.padding(bottom = 2f.gridUnitsAsDp())) {
                                for (line in lines(state)) {
                                    key(line.key) {
                                        when (line) {
                                            is PickerLine.Header -> PickerHeader(line.title)
                                            is PickerLine.Parent -> PickerParent(line) { viewModel.toggleExpanded(line.title) }
                                            is PickerLine.Item -> PickerItem(
                                                line = line,
                                                followed = line.shelved.category.url in state.followed,
                                                adding = state.adding == line.shelved.category.url,
                                                onClick = {
                                                    viewModel.follow(line.shelved.category) { feedId -> goBack(feedId) }
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun lines(state: KagiPickerUiState): List<PickerLine> = buildList {
        val byGroup = state.shelved.groupBy { it.group }

        byGroup[Kagi.Group.CORE]?.let { core ->
            add(PickerLine.Header("Kagi"))
            core.forEach { add(PickerLine.Item(it, indented = false)) }
        }

        byGroup[Kagi.Group.PLACES]?.let { places ->
            add(PickerLine.Header("Places"))
            // Parents in first-seen order, which is Kagi's: the regions with a `|` come in the
            // index's own order, and the countries fold under one row at the end.
            val parents = places.mapNotNull { it.parent }.distinct()
                .sortedWith(compareBy({ it == Kagi.COUNTRIES_PARENT }, { places.indexOfFirst { p -> p.parent == it } }))
            for (parent in parents) {
                val children = places.filter { it.parent == parent }
                val expanded = parent in state.expanded
                add(PickerLine.Parent(parent, children.size, expanded))
                if (expanded) children.forEach { add(PickerLine.Item(it, indented = true)) }
            }
        }

        byGroup[Kagi.Group.TOPICS]?.let { topics ->
            add(PickerLine.Header("Topics"))
            topics.forEach { add(PickerLine.Item(it, indented = false)) }
        }
    }
}

@Composable
private fun PickerHeader(title: String) {
    LightText(
        text = title.uppercase(Locale.US),
        variant = LightTextVariant.Superfine,
        lighten = true,
        modifier = Modifier.padding(start = 1f.gridUnitsAsDp(), end = 1f.gridUnitsAsDp(), top = 1.25f.gridUnitsAsDp(), bottom = 0.25f.gridUnitsAsDp()),
    )
}

@Composable
private fun PickerParent(line: PickerLine.Parent, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(
                onClickLabel = if (line.expanded) "Collapse ${line.title}" else "Expand ${line.title}",
                role = Role.Button,
            ) { onClick() }
            .padding(horizontal = 1f.gridUnitsAsDp(), vertical = 0.6f.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LightText(
            text = line.title,
            variant = LightTextVariant.Paragraph,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        LightText(
            text = if (line.expanded) "${line.count} ▾" else "${line.count} ▸",
            variant = LightTextVariant.Detail,
            lighten = true,
        )
    }
}

@Composable
private fun PickerItem(line: PickerLine.Item, followed: Boolean, adding: Boolean, onClick: () -> Unit) {
    val name = line.shelved.shortName
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(
                onClickLabel = if (followed) "$name, already followed" else "Follow $name",
                role = Role.Button,
                enabled = !followed && !adding,
            ) { onClick() }
            .padding(
                start = if (line.indented) 2.25f.gridUnitsAsDp() else 1f.gridUnitsAsDp(),
                end = 1f.gridUnitsAsDp(),
                top = 0.6f.gridUnitsAsDp(),
                bottom = 0.6f.gridUnitsAsDp(),
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LightText(
            text = name,
            variant = if (line.indented) LightTextVariant.Detail else LightTextVariant.Paragraph,
            lighten = followed,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        when {
            adding -> LightText("…", LightTextVariant.Detail, lighten = true)
            followed -> LightText("FOLLOWING", LightTextVariant.Superfine, lighten = true)
        }
    }
}
