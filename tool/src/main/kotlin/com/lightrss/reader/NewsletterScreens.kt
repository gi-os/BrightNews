package com.lightrss.reader

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.view.MotionEvent
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
// Without this, items(list, key = ...) silently resolves to the items(count: Int, ...) overload.
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.lightrss.reader.gmail.GmailLabel
import com.lightrss.reader.hw.WheelKeys
import com.lightrss.reader.hw.WheelScroll
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightLazyScrollView
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
 * Whether this device can render HTML at all.
 *
 * LightOS is AOSP-derived and does ship a WebView provider today, but a minimal ROM is exactly
 * the kind of build where one goes missing, and instantiating WebView without a provider throws
 * rather than returning null. Probe once and keep a plain-text path behind it, so a LightOS
 * update can degrade the newsletters section instead of breaking it. Nothing on the RSS side
 * touches this — that reader has no browser in it at all.
 *
 * The caller supplies the constructor rather than a Context, because the SDK's build policy
 * blocks importing `android.content.Context` in tool code and there is no way to name the type
 * in a signature. `LocalContext.current` is permitted, so the call site has the value; it just
 * cannot say what it is.
 */
object WebViewSupport {
    @Volatile
    private var cached: Boolean? = null

    fun probe(newWebView: () -> WebView): Boolean = cached ?: synchronized(this) {
        cached ?: runCatching {
            newWebView().also { it.destroy() }
            true
        }.getOrDefault(false).also { cached = it }
    }
}

/**
 * Let the WebView own every gesture inside it.
 *
 * `requestDisallowInterceptTouchEvent(true)` fires on ACTION_DOWN, before anything above can
 * claim the gesture. There is no nested-scroll contract between a View and a Compose scrollable
 * to negotiate this properly, and letting Compose arbitrate meant a fling that started a few
 * degrees off vertical got taken away mid-flight.
 *
 * A touch listener rather than a `WebView` subclass, which is what this was: a subclass has to
 * name `android.content.Context` in its constructor, and the SDK's build policy blocks importing
 * that type. The listener runs ahead of `onTouchEvent` anyway, so nothing is lost — and always
 * returns false, so the WebView still scrolls itself.
 */
private fun WebView.claimGestures() {
    setOnTouchListener { _, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> parent?.requestDisallowInterceptTouchEvent(true)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                parent?.requestDisallowInterceptTouchEvent(false)
        }
        false
    }
}

/**
 * The newsletter itself.
 *
 * A base URL is needed because in-document anchors cannot resolve against an opaque origin —
 * but a deliberately unresolvable one. Point this at a real host and every root-relative path in
 * every newsletter becomes an outbound request to that host: a 404 at best, a first-party
 * tracking request at worst. `.invalid` is reserved by RFC 2606 and can never resolve, so those
 * requests fail locally instead of leaking.
 *
 * http, not https, and not for laziness: an https base makes every plain-http image in the
 * document mixed content, whose handling WebView documents as varying by release. An http base
 * takes mixed content out of the picture, and nothing is ever fetched from the base itself.
 */
@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
@Composable
private fun NewsletterWebView(
    document: String,
    mode: RenderMode,
    loadImages: Boolean,
    onLink: (String) -> Unit,
    onScrolled: (dy: Int, y: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var webRef by remember { mutableStateOf<WebView?>(null) }
    // Held in refs so the listeners always reach the current lambdas rather than the ones
    // captured when the view was created.
    val link = rememberUpdatedState(onLink)
    val scrolled = rememberUpdatedState(onScrolled)
    // Chromium has no idea what WHEEL_CW is, and there is no nested-scroll bridge from a View to
    // Compose, so the wheel is applied by hand to the WebView that is actually on screen.
    WheelScroll(webRef)

    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                claimGestures()
                settings.apply {
                    // Newsletters are documents. No newsletter needs a script, and off is both
                    // faster and one fewer way for an email to do something clever.
                    javaScriptEnabled = false
                    loadsImagesAutomatically = loadImages
                    blockNetworkImage = !loadImages
                    // CSS px on a 1080-wide 3.92" panel is small; floor it.
                    minimumFontSize = 14
                    minimumLogicalFontSize = 14
                    builtInZoomControls = true
                    displayZoomControls = false
                    useWideViewPort = false
                    loadWithOverviewMode = false
                    setSupportZoom(true)
                }
                isVerticalScrollBarEnabled = true
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                setBackgroundColor(backgroundFor(mode))
                // Chromium owns this scroller, so the bars have to be told about it from here.
                // Fires for the wheel too, since that reaches the document through scrollBy.
                setOnScrollChangeListener { _, _, y, _, oldY -> scrolled.value(y - oldY, y) }
                webViewClient = object : WebViewClient() {
                    /**
                     * Nothing is ever browsed inside this view. A tapped link goes to the app's
                     * own reader — the same one the RSS side uses, which fetches the page and
                     * renders it with Light typography and no scripts. LightNews handed these to
                     * the system browser with an ACTION_VIEW intent; the SDK's build policy
                     * blocks `startActivity` and says to navigate instead, and navigating is the
                     * better answer anyway on a phone whose browser you are trying to avoid.
                     */
                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest,
                    ): Boolean {
                        val url = request.url.toString()
                        if (url.startsWith(NEWSLETTER_BASE)) {
                            // An anchor into the same document: let Chromium scroll to it.
                            if (url.startsWith("$NEWSLETTER_BASE#")) return false
                            // Otherwise a root-relative or empty href that resolved against the
                            // synthetic base. Following it would replace the newsletter with a
                            // DNS error page and there is no reload button here, so swallow it.
                            return true
                        }
                        if (url.startsWith("http://") || url.startsWith("https://")) {
                            link.value(url)
                        }
                        // mailto:, tel: and the rest have nowhere to go here, and are dropped
                        // rather than handed to a WebView that would show an error page.
                        return true
                    }
                }
                tag = document
                loadDataWithBaseURL(NEWSLETTER_BASE, document, "text/html", "UTF-8", null)
            }
        },
        update = { web ->
            webRef = web
            web.settings.loadsImagesAutomatically = loadImages
            web.settings.blockNetworkImage = !loadImages
            web.setBackgroundColor(backgroundFor(mode))
            // View.setTag(int, Object) insists on a real resource id, so the single untyped tag
            // remembers which document is already loaded.
            if (web.tag != document) {
                web.tag = document
                web.loadDataWithBaseURL(NEWSLETTER_BASE, document, "text/html", "UTF-8", null)
            }
        },
        onRelease = {
            webRef = null
            it.destroy()
        },
    )
}

private fun backgroundFor(mode: RenderMode) =
    if (mode == RenderMode.DARK) AndroidColor.BLACK else AndroidColor.WHITE

private const val NEWSLETTER_BASE = "http://newsletter.invalid/"

/**
 * Whether the reader's bars are showing.
 *
 * Together they are seven grid units of a twelve-unit-tall panel's worth of chrome — a fifth of
 * the screen, held permanently, on the one screen in the app whose whole job is to show as much
 * of a document as it can. So they go away while you are reading forwards and come back the
 * moment you are not.
 *
 * Two rules keep that from becoming a trap, and both matter more than the hiding does. The bars
 * always return near the top, and they return on any deliberate scroll back up — because the
 * back button lives in the top bar, and a reader who has to guess how to reach it has been given
 * a worse screen, not a bigger one. The upward threshold is deliberately less than half the
 * downward one for the same reason: easy to recover, harder to lose.
 *
 * Travel is accumulated rather than acted on per event. A WebView emits a scroll callback per
 * frame of a fling, and a single pixel of jitter at the end of one would otherwise flip the bars
 * back and forth. A reversal resets the count, so the gesture that counts is the one in progress.
 */
@Stable
private class ChromeVisibility(
    private val nearTopPx: Int,
    private val hideAfterPx: Int,
    private val showAfterPx: Int,
) {
    var visible by mutableStateOf(true)
        private set

    private var travel = 0

    fun onScrolled(dy: Int, y: Int) {
        if (y <= nearTopPx) {
            travel = 0
            visible = true
            return
        }
        travel = if (travel != 0 && (travel > 0) == (dy > 0)) travel + dy else dy
        when {
            travel > hideAfterPx -> {
                visible = false
                travel = 0
            }
            travel < -showAfterPx -> {
                visible = true
                travel = 0
            }
        }
    }
}

@Composable
private fun rememberChromeVisibility(): ChromeVisibility {
    val density = LocalDensity.current
    return remember(density) {
        with(density) {
            ChromeVisibility(
                nearTopPx = 24.dp.roundToPx(),
                hideAfterPx = 56.dp.roundToPx(),
                showAfterPx = 20.dp.roundToPx(),
            )
        }
    }
}

/**
 * The bars, sliding out of the layout rather than floating over it.
 *
 * Light's bars are opaque, so overlaying them would put a solid block across the copy whenever
 * they were up. Taking them out of the Column instead gives the document the space for real —
 * which is the point — at the cost of a reflow, which the short slide covers.
 */
@Composable
private fun ReaderChrome(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(animationSpec = tween(CHROME_MS)) +
            fadeIn(animationSpec = tween(CHROME_MS)),
        exit = shrinkVertically(animationSpec = tween(CHROME_MS)) +
            fadeOut(animationSpec = tween(CHROME_MS)),
    ) {
        content()
    }
}

private const val CHROME_MS = 120

/**
 * Reading one newsletter.
 *
 * A separate screen from [ReaderScreen] because the content is a different kind of thing, not
 * because newsletters are a different app. Everything around it — the star, the read state, the
 * archive — is the same repository call the RSS reader makes.
 */
class NewsletterReaderScreen(
    sealedActivity: SealedLightActivity,
    private val articleId: String,
    private val repository: RssRepository,
) : LightScreen<Unit, NewsletterReaderViewModel>(sealedActivity) {
    override val viewModelClass: Class<NewsletterReaderViewModel> =
        NewsletterReaderViewModel::class.java

    override fun createViewModel() = NewsletterReaderViewModel(articleId, repository)

    @Composable
    override fun Content() {
        val colors by LightThemeController.colors.collectAsState()
        val row by viewModel.article.collectAsState()
        val body by viewModel.body.collectAsState()
        val mode by viewModel.renderMode.collectAsState()
        val loadImages by repository.imagesEnabled.collectAsState(initial = true)
        val colour by repository.colourEnabled.collectAsState(initial = true)
        val article = row?.article
        val context = LocalContext.current
        val scroll = rememberScrollState()
        val chrome = rememberChromeVisibility()

        // Newsletters are the strongest case in the app: brand art, photography and charts, laid
        // out by someone who chose the colours. Only for the HTML path — the plain-text fallback
        // has nothing in it but words.
        ColourEffect(colour && loadImages && body is NewsletterBody.Html)

        LaunchedEffect(context) {
            viewModel.setWebViewAvailable(WebViewSupport.probe { WebView(context) })
        }

        // The plain-text fallback scrolls in Compose rather than in Chromium, so its deltas come
        // from the scroll state instead of a view listener. Same bars, same rules.
        LaunchedEffect(scroll) {
            var last = 0
            snapshotFlow { scroll.value }.collect { y ->
                chrome.onScrolled(y - last, y)
                last = y
            }
        }

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
                        center = LightTopBarCenter.Text(row?.feedTitle ?: "Newsletter"),
                        rightButton = LightBarButton.LightIcon(
                            icon = if (article?.isStarred == true) {
                                LightIcons.STAR
                            } else {
                                LightIcons.STAR_OUTLINE
                            },
                            onClick = viewModel::toggleStar,
                            contentDescription = if (article?.isStarred == true) {
                                "Remove from saved"
                            } else {
                                "Save newsletter"
                            },
                        ),
                    )
                }
                when (val current = body) {
                    null -> LoadingScreen("Opening…", Modifier.weight(1f))

                    is NewsletterBody.Html -> NewsletterWebView(
                        document = current.document,
                        mode = mode,
                        loadImages = loadImages,
                        onLink = { url ->
                            navigateTo({
                                ReaderPageScreen(
                                    it,
                                    // The reader caches fetched pages under this key, so it has
                                    // to be the link and not the issue: a newsletter is a page
                                    // of links, and keying them all on the issue would serve
                                    // whichever one was tapped first for every one after it.
                                    articleId = url,
                                    link = url,
                                    fallbackTitle = article?.title.orEmpty(),
                                    repository = repository,
                                )
                            })
                        },
                        onScrolled = chrome::onScrolled,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    )

                    is NewsletterBody.Text -> {
                        WheelScroll(scroll)
                        LightScrollView(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            scrollState = scroll,
                        ) {
                            LightText(
                                text = current.body,
                                variant = LightTextVariant.Paragraph,
                                modifier = Modifier.padding(
                                    start = 1f.gridUnitsAsDp(),
                                    end = 1f.gridUnitsAsDp(),
                                    top = 0.6f.gridUnitsAsDp(),
                                    bottom = 2f.gridUnitsAsDp(),
                                ),
                            )
                        }
                    }

                    NewsletterBody.Missing -> EmptyState(
                        "This issue has not been downloaded yet.\n\nRefresh the newsletters list.",
                        Modifier.weight(1f),
                    )
                }
                ReaderChrome(chrome.visible) {
                    LightBottomBar(
                        items = listOf(
                            LightBarButton.Text(
                                text = if (mode == RenderMode.DARK) "DARK" else "PAPER",
                                onClick = viewModel::toggleMode,
                            ),
                            LightBarButton.Text(
                                text = if (article?.isRead == true) "UNREAD" else "READ",
                                onClick = viewModel::toggleRead,
                            ),
                            LightBarButton.LightIcon(
                                icon = LightIcons.DELETE,
                                onClick = { viewModel.archive { goBack() } },
                                contentDescription = "Archive",
                            ),
                        ),
                    )
                }
            }
        }
    }
}

/**
 * The mailbox: which account, which labels, and how newsletters are rendered.
 *
 * The newsletters section's equivalent of Subscriptions, and reached the same way — the list
 * button in the top bar.
 */
class MailboxScreen(
    sealedActivity: SealedLightActivity,
    private val repository: RssRepository,
) : LightScreen<Unit, MailboxViewModel>(sealedActivity) {
    override val viewModelClass: Class<MailboxViewModel> = MailboxViewModel::class.java
    override fun createViewModel() = MailboxViewModel(repository)

    @Composable
    override fun Content() {
        val colors by LightThemeController.colors.collectAsState()
        val auth by viewModel.auth.collectAsState()
        val labels by viewModel.labels.collectAsState()
        val mode by viewModel.renderMode.collectAsState()
        val blockAds by viewModel.blockAds.collectAsState()
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
                    center = LightTopBarCenter.Text("Mailbox"),
                    rightButton = if (auth.signedIn) {
                        LightBarButton.LightIcon(
                            LightIcons.ADD,
                            onClick = { navigateTo({ LabelPickerScreen(it, repository) }) },
                            contentDescription = "Follow a label",
                        )
                    } else {
                        null
                    },
                )
                LightScrollView(modifier = Modifier.weight(1f), scrollState = scroll) {
                    when {
                        !auth.configured -> SettingsRow(
                            title = "ADD CLIENT ID",
                            detail = "A Google OAuth client id, typed or scanned",
                        ) {
                            navigateTo({ ClientIdChooserScreen(it, repository) })
                        }

                        !auth.signedIn -> SettingsRow(
                            title = "SIGN IN",
                            detail = "Grant access to one Gmail label",
                        ) {
                            navigateTo({ GmailSignInScreen(it, repository) })
                        }

                        else -> SettingsRow(
                            title = "SIGNED IN",
                            detail = auth.account ?: "Gmail",
                        ) {
                            navigateTo({
                                ConfirmationScreen(
                                    it,
                                    message = "Sign out of Gmail?\n\n" +
                                        "Followed labels stay, but their issues are removed " +
                                        "until you sign in again.",
                                    confirmLabel = "SIGN OUT",
                                )
                            }) { confirmed -> if (confirmed) viewModel.signOut() }
                        }
                    }

                    /*
                     * Always offered once a client id exists, and the reason is worth stating.
                     * A Cloud project usually holds several OAuth clients sharing one numeric
                     * prefix, the console truncates them in its own table, and Google answers a
                     * mismatched one with a flat `invalid_request` at the consent screen that
                     * names neither the client nor the redirect. Without a way to see and
                     * replace the stored id from here, the wrong choice is unrecoverable short
                     * of reinstalling.
                     */
                    if (auth.configured) {
                        SettingsRow(
                            title = "CLIENT ID",
                            detail = auth.clientIdHint ?: "Replace the OAuth client",
                        ) {
                            navigateTo({ ClientIdChooserScreen(it, repository) })
                        }
                        SettingsRow(
                            title = "REMOVE GOOGLE ACCOUNT",
                            detail = "Sign out and forget the OAuth client",
                        ) {
                            navigateTo({
                                ConfirmationScreen(
                                    it,
                                    message = "Remove the Google account?\n\n" +
                                        "The sign-in, the client ID and every downloaded issue " +
                                        "go. Followed labels stay, and fill again once you " +
                                        "connect a mailbox.",
                                    confirmLabel = "REMOVE",
                                )
                            }) { confirmed -> if (confirmed) viewModel.forget() }
                        }
                    }

                    if (labels.isEmpty()) {
                        Column(modifier = Modifier.padding(1f.gridUnitsAsDp())) {
                            LightText("NO LABELS", LightTextVariant.Superfine, lighten = true)
                            LightText(
                                text = "Filter newsletters into a Gmail label, then follow it " +
                                    "here. Nothing outside a followed label is ever read.",
                                variant = LightTextVariant.Detail,
                                modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp()),
                            )
                        }
                    } else {
                        labels.forEach { row ->
                            SettingsRow(
                                title = row.feed.title.uppercase(),
                                detail = detailFor(row),
                            ) {
                                navigateTo({ FeedScreen(it, row.feed.id, repository) })
                            }
                        }
                    }

                    SettingsRow(
                        title = if (mode == RenderMode.DARK) "DARK MODE" else "PAPER MODE",
                        detail = if (mode == RenderMode.DARK) {
                            "White on black, tables unwrapped to fit"
                        } else {
                            "The newsletter's own design, width fixed"
                        },
                        onClick = viewModel::toggleMode,
                    )
                    SettingsRow(
                        title = if (blockAds) "SPONSORS HIDDEN" else "SPONSORS SHOWN",
                        detail = if (blockAds) {
                            "Sponsor blocks are cut, and marked where they were"
                        } else {
                            "Issues are shown exactly as they were sent"
                        },
                        onClick = viewModel::toggleAds,
                    )
                }
                LightBottomBar(
                    items = listOf(
                        LightBarButton.LightIcon(LightIcons.REFRESH, onClick = viewModel::refresh),
                    ),
                )
            }
        }
    }

    private fun detailFor(row: FeedRow): String {
        val error = row.feed.errorMessage
        if (!error.isNullOrBlank()) return error
        return "${row.unreadCount} unread · ${row.articleCount} issues"
    }
}

/** Every label in the mailbox. Tapping one follows it. */
class LabelPickerScreen(
    sealedActivity: SealedLightActivity,
    private val repository: RssRepository,
) : LightScreen<Long, LabelPickerViewModel>(sealedActivity) {
    override val viewModelClass: Class<LabelPickerViewModel> = LabelPickerViewModel::class.java
    override fun createViewModel() = LabelPickerViewModel(repository)

    @Composable
    override fun Content() {
        val colors by LightThemeController.colors.collectAsState()
        val state by viewModel.state.collectAsState()

        WheelKeys()
        LightTheme(colors = colors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(LightIcons.BACK, onClick = { goBack() }),
                    center = LightTopBarCenter.Text("Gmail labels"),
                )
                when {
                    state.isLoading -> LoadingScreen("Reading labels…", Modifier.weight(1f))

                    state.error != null -> Column(modifier = Modifier.weight(1f)) {
                        LightText(
                            text = state.error.orEmpty(),
                            variant = LightTextVariant.Paragraph,
                            modifier = Modifier.padding(1f.gridUnitsAsDp()),
                        )
                        SettingsRow("TRY AGAIN", "Ask Gmail for the label list") { viewModel.load() }
                    }

                    else -> LabelList(
                        labels = state.labels,
                        onPick = { label ->
                            viewModel.subscribe(
                                label = label,
                                onAdded = { feedId -> goBack(feedId) },
                                onError = { message -> navigateTo({ MessageScreen(it, message) }) },
                            )
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun LabelList(
    labels: List<GmailLabel>,
    onPick: (GmailLabel) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (labels.isEmpty()) {
        EmptyState("This mailbox has no labels.", modifier)
        return
    }
    LightLazyScrollView(modifier = modifier, uniformItemHeightGridUnits = LABEL_ROW_HEIGHT) {
        items(labels, key = { it.id }) { label ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(LABEL_ROW_HEIGHT.gridUnitsAsDp())
                    .lightClickable(onClickLabel = label.name, role = Role.Button) { onPick(label) }
                    .padding(horizontal = 1f.gridUnitsAsDp(), vertical = 0.5f.gridUnitsAsDp()),
            ) {
                LightText(
                    text = label.name,
                    variant = LightTextVariant.Paragraph,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                LightText(
                    text = if (label.isUser) "YOUR LABEL" else "GMAIL",
                    variant = LightTextVariant.Superfine,
                    lighten = true,
                    modifier = Modifier.padding(top = 0.25f.gridUnitsAsDp()),
                )
            }
        }
    }
}

/** The scroll bar needs a fixed row height; two lines of Light type comes to this. */
private const val LABEL_ROW_HEIGHT = 3.6f

/**
 * Google's consent page, in a WebView the tool owns.
 *
 * LightNews handed this to the system browser and waited for a custom-scheme redirect to come
 * back through an intent filter. A Light SDK tool has no manifest and owns no activity, so there
 * is nothing for the OS to hand it to — and on top of that, the redirect only ever came back if
 * the LightOS browser chose to honour it. Here the redirect is caught in
 * `shouldOverrideUrlLoading` and never fetched at all, so it does not have to resolve, be
 * registered, or leave the process.
 */
class GmailSignInScreen(
    sealedActivity: SealedLightActivity,
    private val repository: RssRepository,
) : LightScreen<Boolean, GmailSignInViewModel>(sealedActivity) {
    override val viewModelClass: Class<GmailSignInViewModel> = GmailSignInViewModel::class.java
    override fun createViewModel() = GmailSignInViewModel(repository)

    @SuppressLint("SetJavaScriptEnabled")
    @Composable
    override fun Content() {
        val colors by LightThemeController.colors.collectAsState()
        val state by viewModel.state.collectAsState()
        val url = state.url
        var webRef by remember { mutableStateOf<WebView?>(null) }

        // Held in a ref so the WebViewClient always reaches the current screen rather than the
        // one captured when the view was created.
        val finish = rememberUpdatedState<(String) -> Unit> { redirect ->
            viewModel.complete(redirect) { ok ->
                if (ok) {
                    goBack(true)
                } else {
                    navigateTo({
                        MessageScreen(
                            it,
                            "Sign-in did not complete.\n\n" +
                                "Google returns a refresh token only on a first grant — if this " +
                                "account has authorised the app before, revoke it at " +
                                "myaccount.google.com and try again.",
                        )
                    })
                }
            }
        }

        WheelKeys()
        WheelScroll(webRef)
        LightTheme(colors = colors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(LightIcons.BACK, onClick = { goBack(false) }),
                    center = LightTopBarCenter.Text("Google sign-in"),
                )
                when {
                    state.finishing -> LoadingScreen("Finishing sign-in…", Modifier.weight(1f))
                    url == null -> LoadingScreen(
                        state.error ?: "Preparing…",
                        Modifier.weight(1f),
                    )

                    else -> AndroidView(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        factory = { context ->
                            WebView(context).apply {
                                // Google's consent screen is an application, not a document.
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.useWideViewPort = true
                                settings.loadWithOverviewMode = true
                                settings.builtInZoomControls = true
                                settings.displayZoomControls = false
                                CookieManager.getInstance().setAcceptCookie(true)
                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(
                                        view: WebView,
                                        request: WebResourceRequest,
                                    ): Boolean {
                                        val target = request.url.toString()
                                        // The redirect carries the authorization code in its
                                        // query. Swallowing it here is the whole mechanism —
                                        // it is never fetched, so it never has to resolve.
                                        if (isRedirect(target)) {
                                            finish.value(target)
                                            return true
                                        }
                                        return false
                                    }
                                }
                                loadUrl(url)
                            }
                        },
                        update = { webRef = it },
                        onRelease = {
                            webRef = null
                            it.destroy()
                        },
                    )
                }
            }
        }
    }

    /**
     * Matched against the string rather than asked of the suspending API, because
     * `shouldOverrideUrlLoading` cannot suspend and must answer before the request is made.
     * Both accepted shapes are recognisable without a round trip to storage.
     */
    private fun isRedirect(url: String): Boolean =
        url.startsWith("http://127.0.0.1") ||
            url.startsWith("http://localhost") ||
            url.startsWith("com.lightrss.reader:")
}

/** Chooser for the OAuth credentials: scan a code, or type one in. */
class ClientIdChooserScreen(
    sealedActivity: SealedLightActivity,
    private val repository: RssRepository,
) : LightScreen<Unit, MenuViewModel>(sealedActivity) {
    override val viewModelClass: Class<MenuViewModel> = MenuViewModel::class.java
    override fun createViewModel() = MenuViewModel()

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
                    center = LightTopBarCenter.Text("Gmail access"),
                )
                SettingsRow("SCAN QR CODE", "Point the camera at the id on another screen") {
                    navigateTo({ ClientIdScreen(it, repository, scan = true) }) { goBack() }
                }
                SettingsRow("PASTE OR TYPE", "Phone keyboard, with a paste button") {
                    navigateTo({ ClientIdScreen(it, repository, scan = false) }) { goBack() }
                }
                Column(modifier = Modifier.padding(1f.gridUnitsAsDp())) {
                    LightText("WHAT THIS IS", LightTextVariant.Superfine, lighten = true)
                    LightText(
                        text = "A Google OAuth client id from console.cloud.google.com, ending " +
                            "in .apps.googleusercontent.com. Create a Desktop-type client, " +
                            "enable the Gmail API, and add your address as a test user.",
                        variant = LightTextVariant.Detail,
                        modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp()),
                    )
                    LightText(
                        text = "The output of scripts/authorize.py works here too, and skips " +
                            "the consent screen on the phone entirely.",
                        variant = LightTextVariant.Detail,
                        modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp()),
                    )
                }
            }
        }
    }
}

class ClientIdScreen(
    sealedActivity: SealedLightActivity,
    private val repository: RssRepository,
    private val scan: Boolean,
) : LightScreen<Unit, ClientIdViewModel>(sealedActivity) {
    override val viewModelClass: Class<ClientIdViewModel> = ClientIdViewModel::class.java
    override fun createViewModel() = ClientIdViewModel(repository)

    @Composable
    override fun Content() {
        val colors by LightThemeController.colors.collectAsState()
        val state by viewModel.state.collectAsState()
        val input = key(state.inputSession) { rememberTextFieldState("") }
        val keyboard = rememberKeyboardOptions()
        var lightKeys by rememberSaveable { mutableStateOf(false) }

        LaunchedEffect(state.error) {
            val message = state.error ?: return@LaunchedEffect
            viewModel.clearError()
            navigateTo({ MessageScreen(it, message) })
        }

        val submit: (CharSequence) -> Unit = { raw ->
            viewModel.submit(raw) { goBack() }
        }

        LightTheme(colors = colors) {
            when {
                scan -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(LightThemeTokens.colors.background),
                ) {
                    LightTopBar(
                        leftButton = LightBarButton.LightIcon(LightIcons.BACK, onClick = { goBack() }),
                        center = LightTopBarCenter.Text("Scan client id"),
                    )
                    key(state.inputSession) {
                        FeedQrScanner(
                            onScanned = { scanned -> submit(scanned) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                lightKeys -> LightTextInputEditor(
                    title = "Client ID",
                    editorKey = state.inputSession,
                    keyboardOptionsFlow = keyboard,
                    state = input,
                    onSubmit = submit,
                    onBack = { lightKeys = false },
                    submitIcon = LightIcons.ADD,
                    showBackButton = true,
                    modifier = Modifier.fillMaxSize(),
                )

                else -> SystemTextEntry(
                    title = "Client ID",
                    state = input,
                    submitLabel = "SAVE",
                    hint = "…apps.googleusercontent.com",
                    onSubmit = submit,
                    onBack = { goBack() },
                    onUseLightKeys = { lightKeys = true },
                )
            }
        }
    }
}
