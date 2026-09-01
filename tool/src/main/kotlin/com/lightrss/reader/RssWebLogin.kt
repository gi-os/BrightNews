package com.lightrss.reader

import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.lightrss.reader.hw.WheelScroll
import com.lightrss.reader.hw.WheelKeys
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch

/** Stores whatever the sign-in view earned, so reader fetches can use it. */
class SignInViewModel(
    private val url: String,
    private val repository: RssRepository,
) : LightViewModel<Unit>() {

    fun keep(cookies: String?, userAgent: String?) {
        // DONE and BACK pop this screen, which clears its ViewModelStore and cancels this
        // scope — routinely before the write below has run, so the sign-in that was just
        // earned never landed. NonCancellable detaches the persist from the screen's lifetime.
        viewModelScope.launch(Dispatchers.IO + NonCancellable) {
            repository.setSiteAccess(url, cookies, userAgent)
        }
    }
}

/**
 * Signs in, or clears a bot check, inside the app rather than in another browser.
 *
 * Handing the link to Chrome does not help: the cookie that gets you past the wall lands in
 * Chrome, and the reader keeps being turned away. This screen loads the page here, keeps the
 * cookies it collects, and records the user agent that earned them, so the next reader fetch
 * arrives looking like the same client.
 *
 * This is the one place in the app where a page's own scripts run.
 */
class SignInScreen(
    sealedActivity: SealedLightActivity,
    private val url: String,
    private val repository: RssRepository,
) : LightScreen<Unit, SignInViewModel>(sealedActivity) {
    override val viewModelClass: Class<SignInViewModel> = SignInViewModel::class.java
    override fun createViewModel() = SignInViewModel(url, repository)

    @Composable
    override fun Content() {
        val colors by LightThemeController.colors.collectAsState()
        val webView = remember { mutableStateOf<WebView?>(null) }
        // Named to keep it out of reach of WebView.getUrl() inside the builder below.
        val pageUrl = url

        fun keepCookies() {
            val view = webView.value
            val manager = CookieManager.getInstance()
            manager.flush()
            viewModel.keep(manager.getCookie(pageUrl), view?.settings?.userAgentString)
        }

        WheelKeys()
        WheelScroll(webView.value)

        DisposableEffect(Unit) {
            onDispose {
                keepCookies()
                webView.value?.destroy()
                webView.value = null
            }
        }

        LightTheme(colors = colors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(
                        LightIcons.BACK,
                        onClick = {
                            keepCookies()
                            goBack(Unit)
                        },
                    ),
                    center = LightTopBarCenter.Text(sourceHost(pageUrl).ifBlank { "Sign in" }),
                )
                AndroidView(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    factory = { context ->
                        val manager = CookieManager.getInstance()
                        manager.setAcceptCookie(true)
                        WebView(context).apply {
                            manager.setAcceptThirdPartyCookies(this, true)
                            // A challenge page cannot be cleared without running its scripts.
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            webViewClient = WebViewClient()
                            loadUrl(pageUrl)
                            webView.value = this
                        }
                    },
                )
                LightBottomBar(
                    items = listOf(
                        LightBarButton.Text(
                            text = "DONE",
                            onClick = {
                                keepCookies()
                                goBack(Unit)
                            },
                        ),
                        LightBarButton.Text(
                            text = "RELOAD",
                            onClick = { webView.value?.reload() },
                        ),
                    ),
                )
            }
        }
    }
}
