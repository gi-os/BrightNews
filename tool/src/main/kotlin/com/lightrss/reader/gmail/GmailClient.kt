package com.lightrss.reader.gmail

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

class GmailHttpError(val code: Int, message: String) : IOException("HTTP $code: $message")

/**
 * The four Gmail endpoints this app needs. The official client library would pull in GAX,
 * Guava and a service-account stack for the same result.
 */
class GmailClient(private val auth: GmailAuth) {

    private val http = HttpClient(OkHttp) {
        expectSuccess = false
        install(HttpTimeout) {
            requestTimeoutMillis = 45_000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 30_000
        }
    }

    /** Gmail addresses labels by opaque id, so the human name has to be looked up. */
    suspend fun findLabelId(name: String): String? {
        val labels = get("labels").optJSONArray("labels") ?: return null
        var fallback: String? = null
        for (i in 0 until labels.length()) {
            val label = labels.getJSONObject(i)
            val labelName = label.optString("name")
            if (labelName == name) return label.optString("id")
            // Nested labels are "Parent/Child"; match the leaf so moving the label under a
            // parent in Gmail doesn't silently empty the app.
            if (labelName.substringAfterLast('/') == name) fallback = label.optString("id")
        }
        return fallback
    }

    /** Every label in the mailbox, for the picker. User labels first, Gmail's own after. */
    suspend fun listLabels(): List<GmailLabel> {
        val labels = get("labels").optJSONArray("labels") ?: return emptyList()
        return (0 until labels.length())
            .map { labels.getJSONObject(it) }
            .map { GmailLabel(it.optString("id"), it.optString("name"), it.optString("type") == "user") }
            .filter { it.id.isNotEmpty() && it.name.isNotEmpty() }
            .sortedWith(compareByDescending<GmailLabel> { it.isUser }.thenBy { it.name.lowercase() })
    }

    /**
     * Message ids carrying [labelId], newest first.
     *
     * [ListPage.complete] is false when Gmail paginated, which is the signal not to treat
     * absent ids as removed from the label.
     */
    suspend fun listIds(labelId: String, unreadOnly: Boolean, max: Int): ListPage {
        val json = get("messages") {
            parameter("labelIds", labelId)
            parameter("maxResults", max.toString())
            if (unreadOnly) parameter("labelIds", "UNREAD")
        }
        val array = json.optJSONArray("messages")
        val ids = buildList {
            for (i in 0 until (array?.length() ?: 0)) add(array!!.getJSONObject(i).getString("id"))
        }
        return ListPage(ids, complete = json.optString("nextPageToken").isEmpty())
    }

    suspend fun fetch(id: String): RawMessage =
        MimeParser.parse(get("messages/$id") { parameter("format", "FULL") })

    /** Inline images referenced as cid:, fetched only when images are switched on. */
    suspend fun attachment(messageId: String, attachmentId: String): ByteArray =
        MimeParser.decodeBody(get("messages/$messageId/attachments/$attachmentId").optString("data"))

    /**
     * Clear UNREAD on one or many messages. batchModify is one request for the lot, which
     * matters when catching up on a hundred issues at once — a hundred serial modify calls is
     * how you get rate limited.
     */
    suspend fun markRead(ids: List<String>) {
        if (ids.isEmpty()) return
        if (ids.size == 1) {
            post(
                "messages/${ids.first()}/modify",
                JSONObject().put("removeLabelIds", JSONArray().put("UNREAD")).toString(),
            )
            return
        }
        // batchModify returns 204 with an empty body, which call() already tolerates.
        ids.chunked(BATCH_LIMIT).forEach { chunk ->
            post(
                "messages/batchModify",
                JSONObject()
                    .put("ids", JSONArray(chunk))
                    .put("removeLabelIds", JSONArray().put("UNREAD"))
                    .toString(),
            )
        }
    }

    fun close() = http.close()

    private suspend fun get(
        path: String,
        block: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {},
    ): JSONObject = call { token ->
        http.get("$BASE/$path") {
            header(HttpHeaders.Authorization, "Bearer $token")
            block()
        }
    }

    private suspend fun post(path: String, body: String): JSONObject = call { token ->
        http.post("$BASE/$path") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(body)
        }
    }

    /**
     * One retry on 401. A token that expired on the clock is refreshed before the request goes
     * out; a token revoked mid-session only surfaces as a rejection, so force a refresh and try
     * once more before giving up.
     */
    private suspend fun call(request: suspend (String) -> HttpResponse): JSONObject {
        repeat(2) { attempt ->
            val response = request(auth.accessToken())
            val body = response.bodyAsText()
            val code = response.status.value
            when {
                code in 200..299 -> return if (body.isBlank()) JSONObject() else JSONObject(body)
                code == 401 && attempt == 0 -> auth.invalidateAccessToken()
                else -> throw GmailHttpError(code, body.take(300))
            }
        }
        throw GmailHttpError(401, "still unauthorised after refresh")
    }

    companion object {
        private const val BASE = "https://gmail.googleapis.com/gmail/v1/users/me"

        /** Gmail's documented ceiling for batchModify. */
        private const val BATCH_LIMIT = 1000
    }
}

data class ListPage(val ids: List<String>, val complete: Boolean)

data class GmailLabel(val id: String, val name: String, val isUser: Boolean)
