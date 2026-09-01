package com.lightrss.reader.gmail

import android.util.Base64
import android.webkit.CookieManager
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Parameters
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

/** The refresh token was revoked; only a fresh consent will fix it. */
class ReauthRequired(message: String) : IOException(message)

/**
 * Where the OAuth credentials live.
 *
 * Deliberately not SharedPreferences. A Light SDK tool has no Application of its own — it is
 * loaded into the LightOS server process — and the reader already keeps its other secrets
 * (per-host sign-in cookies, see [com.lightrss.reader.RssRepository.setSiteAccess]) in the
 * `app_metadata` table. One store, one lifetime, one thing to wipe on sign-out.
 */
interface AuthStore {
    suspend fun read(key: String): String?
    suspend fun write(key: String, value: String?)
}

/** What the settings screen needs to know without suspending. */
data class GmailAuthState(
    val configured: Boolean = false,
    val signedIn: Boolean = false,
    val account: String? = null,
    /** The stored client id, so the settings screen can show which one is in use. */
    val clientId: String? = null,
) {
    /**
     * Enough of the client id to tell two of them apart, and no more.
     *
     * A project usually has several clients sharing one numeric prefix — an Android one and a
     * Desktop one, say — and picking the wrong one is the single easiest mistake to make here,
     * because the Cloud console truncates them in its own table. The distinguishing part is the
     * random segment after the dash, so that is what this keeps.
     */
    val clientIdHint: String?
        get() = clientId?.removeSuffix(".apps.googleusercontent.com")?.let { id ->
            if (id.length <= 22) id else id.take(18) + "…"
        }
}

/**
 * OAuth against Google, by hand, inside the app.
 *
 * LightNews ran this through the system browser: a plain `ACTION_VIEW` to the consent page
 * and a custom-scheme redirect caught by an intent filter on its `MainActivity`. That cannot
 * work here — a Light SDK tool ships no manifest and owns no activity, so there is nothing
 * for the OS to hand the redirect back to.
 *
 * So the consent page is loaded in a WebView the tool owns (see `GmailSignInScreen`) and the
 * redirect is intercepted in `shouldOverrideUrlLoading` before it is ever fetched. This is
 * strictly better than what it replaces: it needs no browser on the phone, no intent filter,
 * no package-visibility guesswork, and the code never leaves the process.
 *
 * Because the redirect is intercepted rather than routed, it does not have to be resolvable.
 * The default is a loopback address, which is what Google registers for a **Desktop** client
 * type — the same client `scripts/authorize.py` uses, so one set of credentials covers both
 * the on-phone flow and the desktop escape hatch. A custom scheme from an Android-type client
 * is intercepted just as happily; see [redirectUri].
 */
class GmailAuth(private val store: AuthStore) {

    private val lock = Mutex()
    private val _state = MutableStateFlow(GmailAuthState())
    val state: StateFlow<GmailAuthState> = _state.asStateFlow()

    private val http = HttpClient(OkHttp) {
        expectSuccess = false
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 15_000
        }
    }

    /** Reads the store once and publishes it, so the UI can render without suspending. */
    suspend fun refreshState() {
        val id = clientId()
        _state.value = GmailAuthState(
            configured = id.isNotEmpty(),
            signedIn = store.read(KEY_REFRESH)?.isNotBlank() == true,
            account = store.read(KEY_EMAIL),
            clientId = id.takeIf { it.isNotEmpty() },
        )
    }

    /* ------------------------------------------------------------- configuration */

    suspend fun clientId(): String = store.read(KEY_CLIENT_ID)?.trim().orEmpty()

    private suspend fun clientSecret(): String? = store.read(KEY_CLIENT_SECRET)?.takeIf { it.isNotBlank() }

    /**
     * Where consent comes back to. Never navigated to — [isRedirect] catches it in the
     * WebView first — so it only has to be a string Google will accept for this client type.
     */
    suspend fun redirectUri(): String =
        store.read(KEY_REDIRECT)?.takeIf { it.isNotBlank() } ?: DEFAULT_REDIRECT

    /** True for the URL the WebView must not load, because it carries the authorization code. */
    suspend fun isRedirect(url: String): Boolean = url.startsWith(redirectUri())

    /**
     * Store a client id typed or scanned in. Returns false rather than letting the flow fail
     * with `invalid_client` three screens later.
     *
     * Changing it drops any existing credentials: a refresh token belongs to the client that
     * issued it.
     */
    suspend fun setClientId(raw: String): Boolean {
        // Tolerate a whole local.properties line, and stray quotes from a paste.
        val cleaned = raw.trim()
            .removePrefix("lightnews:")
            .removePrefix("lightnews://")
            .removePrefix("gmailClientId=")
            .trim()
            .trim('"')
        if (!cleaned.endsWith(CLIENT_ID_SUFFIX) || cleaned.length <= CLIENT_ID_SUFFIX.length) {
            return false
        }
        if (cleaned == clientId()) return true
        lock.withLock {
            store.write(KEY_CLIENT_ID, cleaned)
            listOf(KEY_CLIENT_SECRET, KEY_REFRESH, KEY_ACCESS, KEY_EXPIRY, KEY_EMAIL)
                .forEach { store.write(it, null) }
        }
        refreshState()
        return true
    }

    /**
     * Adopt credentials obtained on a computer by `scripts/authorize.py`. The path that needs
     * no consent screen on the phone at all — scan the JSON as a QR code and you are signed in.
     */
    suspend fun setCredentials(json: JSONObject): Boolean {
        val id = json.optString("client_id").trim()
        val secret = json.optString("client_secret").trim()
        val refresh = json.optString("refresh_token").trim()
        if (!id.endsWith(CLIENT_ID_SUFFIX) || refresh.isEmpty()) return false
        lock.withLock {
            store.write(KEY_CLIENT_ID, id)
            store.write(KEY_CLIENT_SECRET, secret.ifEmpty { null })
            store.write(KEY_REFRESH, refresh)
            store.write(KEY_EMAIL, json.optString("email").ifBlank { null })
            json.optString("redirect_uri").trim().takeIf { it.isNotEmpty() }
                ?.let { store.write(KEY_REDIRECT, it) }
            store.write(KEY_ACCESS, null)
            store.write(KEY_EXPIRY, null)
        }
        refreshState()
        return true
    }

    /* ------------------------------------------------------------ authorization */

    /** The consent URL, with a fresh PKCE verifier and a state for the redirect to prove. */
    suspend fun authorizationUrl(): String {
        val verifier = randomUrlSafe(64)
        val state = randomUrlSafe(16)
        store.write(KEY_VERIFIER, verifier)
        store.write(KEY_STATE, state)

        val params = listOf(
            "client_id" to clientId(),
            "redirect_uri" to redirectUri(),
            "response_type" to "code",
            "scope" to SCOPES,
            "code_challenge" to challengeOf(verifier),
            "code_challenge_method" to "S256",
            "state" to state,
            // offline for a refresh token, and consent every time because Google issues one
            // only on the first grant — a re-authorisation without it comes back with an
            // access token and no way to renew it.
            "access_type" to "offline",
            "prompt" to "consent",
        )
        return params.joinToString("&", prefix = "$AUTH_ENDPOINT?") { (k, v) ->
            "$k=" + URLEncoder.encode(v, "UTF-8")
        }
    }

    /**
     * Handle the redirect the WebView was about to load. False for a user who backed out, and
     * for a state mismatch — the check that stops a page in the consent flow from feeding us
     * an authorization code of its own.
     */
    suspend fun onRedirect(url: String): Boolean {
        val query = runCatching { URI(url).rawQuery }.getOrNull().orEmpty()
        val fields = query.split('&').mapNotNull { pair ->
            val name = pair.substringBefore('=', "")
            if (name.isEmpty()) return@mapNotNull null
            name to decode(pair.substringAfter('=', ""))
        }.toMap()

        val expectedState = store.read(KEY_STATE)
        val verifier = store.read(KEY_VERIFIER)
        store.write(KEY_STATE, null)
        store.write(KEY_VERIFIER, null)

        if (expectedState == null || verifier == null) return false
        if (fields["state"] != expectedState) return false
        val code = fields["code"]?.takeIf { it.isNotBlank() } ?: return false

        val body = runCatching {
            form(
                "grant_type" to "authorization_code",
                "code" to code,
                "client_id" to clientId(),
                "redirect_uri" to redirectUri(),
                "code_verifier" to verifier,
                "client_secret" to clientSecret(),
            )
        }.getOrNull() ?: return false

        val refresh = body.optString("refresh_token").takeIf { it.isNotBlank() } ?: return false
        lock.withLock {
            store.write(KEY_REFRESH, refresh)
            store.write(KEY_ACCESS, body.optString("access_token"))
            store.write(KEY_EXPIRY, expiryFrom(body.optInt("expires_in", 0)).toString())
            store.write(KEY_EMAIL, emailFromIdToken(body.optString("id_token")))
        }
        refreshState()
        return true
    }

    /* ------------------------------------------------------------------- tokens */

    /**
     * A valid access token, refreshing if needed.
     *
     * invalid_grant here is almost always the seven-day expiry Google applies to consent
     * screens still in Testing. Nothing is recoverable at that point, so drop the credentials
     * and make the UI ask for consent again rather than retrying forever.
     */
    suspend fun accessToken(): String = lock.withLock {
        val cached = store.read(KEY_ACCESS)
        val expiry = store.read(KEY_EXPIRY)?.toLongOrNull() ?: 0L
        if (!cached.isNullOrBlank() && System.currentTimeMillis() < expiry) return cached

        val refresh = store.read(KEY_REFRESH)?.takeIf { it.isNotBlank() }
            ?: throw ReauthRequired("not signed in")

        val body = try {
            form(
                "grant_type" to "refresh_token",
                "refresh_token" to refresh,
                "client_id" to clientId(),
                "client_secret" to clientSecret(),
            )
        } catch (e: TokenError) {
            if (e.error == "invalid_grant") {
                clearTokens()
                // Publish the sign-out too, or Settings keeps saying SIGNED IN over
                // credentials that no longer exist.
                refreshState()
                throw ReauthRequired("authorisation expired")
            }
            throw e
        }
        val access = body.optString("access_token").takeIf { it.isNotBlank() }
            ?: throw IOException("token response carried no access_token")
        store.write(KEY_ACCESS, access)
        store.write(KEY_EXPIRY, expiryFrom(body.optInt("expires_in", 0)).toString())
        access
    }

    /** Force the next [accessToken] to hit the token endpoint. */
    suspend fun invalidateAccessToken() = lock.withLock { store.write(KEY_EXPIRY, null) }

    suspend fun signOut() {
        lock.withLock { clearTokens() }
        clearWebSession()
        refreshState()
    }

    /**
     * Sign out *and* forget which OAuth client was being used.
     *
     * [signOut] deliberately keeps the client id, on the grounds that it is device
     * configuration rather than an account. That is right until the id itself is the problem —
     * the wrong one of a project's several clients, or one whose secret has been rotated — and
     * then keeping it is the difference between an app you can fix and one you have to
     * reinstall. Google answers a mismatched client with a flat `invalid_request` at the
     * consent screen, which says nothing about which of the two is wrong, so there has to be a
     * way back out.
     */
    suspend fun forget() {
        lock.withLock {
            clearTokens()
            listOf(KEY_CLIENT_ID, KEY_CLIENT_SECRET, KEY_REDIRECT).forEach { store.write(it, null) }
        }
        clearWebSession()
        refreshState()
    }

    /**
     * Drop the Google session the consent WebView left behind.
     *
     * Throwing the tokens away is not signing out. Consent runs in a WebView this process owns,
     * with cookies accepted (see `GmailSignInScreen`), so Google's own session cookies outlive
     * the refresh token — and the next sign-in walks straight past the account chooser back into
     * the account that just left. That is the wrong account's mail on somebody's phone, and it
     * looks like the sign-out silently failed.
     *
     * There is no per-domain removal in [CookieManager], so this takes the whole jar. That is
     * safe here: the reader's per-host RSS sign-ins are not kept in it — they are stored in
     * `app_metadata` and sent as an explicit Cookie header, see
     * [com.lightrss.reader.RssRepository.setSiteAccess].
     *
     * [CookieManager] takes no Context, but it does need the WebView provider, which is only
     * safe to bring up on the main thread. A device with no usable WebView must not turn sign-out
     * into a crash either, so a failure here is swallowed: the tokens are already gone.
     */
    private suspend fun clearWebSession() {
        withContext(Dispatchers.Main) {
            runCatching {
                val cookies = CookieManager.getInstance()
                cookies.removeAllCookies(null)
                cookies.flush()
            }
        }
    }

    private suspend fun clearTokens() {
        // The client id survives here; see [forget] for when it should not.
        listOf(KEY_REFRESH, KEY_ACCESS, KEY_EXPIRY, KEY_EMAIL, KEY_STATE, KEY_VERIFIER)
            .forEach { store.write(it, null) }
    }

    fun close() = http.close()

    /* ----------------------------------------------------------------- plumbing */

    private class TokenError(val error: String, message: String) : IOException(message)

    private suspend fun form(vararg fields: Pair<String, String?>): JSONObject {
        val response = http.submitForm(
            url = TOKEN_ENDPOINT,
            formParameters = Parameters.build {
                fields.forEach { (name, value) -> if (value != null) append(name, value) }
            },
        )
        val text = response.bodyAsText()
        val json = runCatching { JSONObject(text) }.getOrNull() ?: JSONObject()
        if (!response.status.isSuccess()) {
            throw TokenError(json.optString("error"), "HTTP ${response.status.value}: ${text.take(200)}")
        }
        return json
    }

    /** A minute of slack, so a token cannot expire between the check and the request. */
    private fun expiryFrom(expiresInSeconds: Int): Long =
        System.currentTimeMillis() + (expiresInSeconds.coerceAtLeast(60) - 60) * 1000L

    /**
     * The email claim, straight off the JWT payload. Google has just issued this over TLS, so
     * there is nothing to verify here — it only reads a claim for display.
     */
    private fun emailFromIdToken(jwt: String): String? = runCatching {
        val payload = jwt.split('.').getOrNull(1) ?: return@runCatching null
        val json = String(
            Base64.decode(payload, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING),
            Charsets.UTF_8,
        )
        JSONObject(json).optString("email").ifBlank { null }
    }.getOrNull()

    private fun decode(raw: String): String =
        runCatching { java.net.URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)

    private fun randomUrlSafe(bytes: Int): String {
        val buffer = ByteArray(bytes)
        SecureRandom().nextBytes(buffer)
        return Base64.encodeToString(buffer, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private fun challengeOf(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    companion object {
        const val KEY_CLIENT_ID = "gmail_client_id"
        const val KEY_CLIENT_SECRET = "gmail_client_secret"
        const val KEY_REDIRECT = "gmail_redirect_uri"
        private const val KEY_REFRESH = "gmail_refresh_token"
        private const val KEY_ACCESS = "gmail_access_token"
        private const val KEY_EXPIRY = "gmail_access_expiry"
        private const val KEY_EMAIL = "gmail_account_email"
        private const val KEY_VERIFIER = "gmail_pkce_verifier"
        private const val KEY_STATE = "gmail_auth_state"
        private const val CLIENT_ID_SUFFIX = ".apps.googleusercontent.com"

        /**
         * Loopback, because Google registers exactly this shape for a Desktop-type client and
         * nothing here ever binds the port — the WebView answers the request by refusing to
         * make it. Overridable from scanned credentials for an Android-type client, whose
         * redirect is a custom scheme instead.
         */
        const val DEFAULT_REDIRECT = "http://127.0.0.1:8731/oauth2redirect"

        private const val AUTH_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth"
        private const val TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"
        private const val SCOPES = "openid email https://www.googleapis.com/auth/gmail.modify"
    }
}
