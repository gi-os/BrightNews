package com.lightrss.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.lightrss.reader.hw.WheelKeys
import com.lightrss.reader.hw.WheelScroll
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
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
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The last crash, kept until somebody has read it.
 *
 * A sideloaded app on a phone with no cable to hand is a black box: it either works or it "just
 * closes", and the stack trace — the one thing that would settle it in a second — is in a logcat
 * nobody can reach. So the handler writes it to a file, and the next launch shows it and sends it
 * to `gi-os/light-reports`, the way the other Bright* apps do. Ported from BrightNotebook's
 * `report/CrashLog`, minus the Context: a tool gets its files directory from the SDK.
 *
 * Nothing is sent from in here. Writing the file is the last thing a dying process does and it
 * has no business opening a socket; the report goes out on the next launch, from a healthy one.
 */
object CrashLog {

    private const val FILE = "last-crash.txt"

    /** Chain onto whatever was already installed rather than replacing it. Idempotent. */
    fun install(filesDir: File) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous is Handler) return
        Thread.setDefaultUncaughtExceptionHandler(Handler(filesDir, previous))
    }

    private class Handler(
        private val filesDir: File,
        private val previous: Thread.UncaughtExceptionHandler?,
    ) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(thread: Thread, error: Throwable) {
            runCatching { write(filesDir, thread, error) }
            // Always hand on: swallowing it would leave the process wedged instead of dying,
            // which is worse than crashing and is not this object's decision to make.
            previous?.uncaughtException(thread, error)
        }
    }

    /** The stored trace, or null when the last run ended the way it was supposed to. */
    fun read(filesDir: File): String? =
        File(filesDir, FILE).takeIf { it.exists() }?.runCatching { readText() }?.getOrNull()

    fun clear(filesDir: File) {
        runCatching { File(filesDir, FILE).delete() }
    }

    private fun write(filesDir: File, thread: Thread, error: Throwable) {
        val stack = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val at = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        File(filesDir, FILE).writeText(
            buildString {
                appendLine("News v${BuildConfig.VERSION_NAME}")
                appendLine("at: $at")
                appendLine("thread: ${thread.name}")
                appendLine()
                append(stack)
            },
        )
    }

    /**
     * Files the trace as an issue. True when GitHub took it. Silently false with no token in the
     * build or no network — the trace stays on screen either way, so it can still be read off.
     */
    suspend fun send(trace: String): Boolean {
        if (BuildConfig.REPORT_TOKEN.isBlank()) return false
        val firstLine = trace.lineSequence().firstOrNull { it.contains("Exception") || it.contains("Error") }
            ?.trim()?.take(90) ?: "crash"
        val body = buildJsonObject {
            put("title", "News v${BuildConfig.VERSION_NAME} — crash: $firstLine")
            put("body", "```\n${trace.take(60_000)}\n```")
            put("labels", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("crash")); add(kotlinx.serialization.json.JsonPrimitive("news")) })
        }
        return withContext(Dispatchers.IO) {
            runCatching {
                val client = HttpClient(OkHttp)
                try {
                    val response: HttpResponse = client.post("https://api.github.com/repos/${BuildConfig.REPORT_REPO}/issues") {
                        header(HttpHeaders.Authorization, "Bearer ${BuildConfig.REPORT_TOKEN}")
                        header(HttpHeaders.Accept, "application/vnd.github+json")
                        header("X-GitHub-Api-Version", "2022-11-28")
                        contentType(ContentType.Application.Json)
                        setBody(body.toString())
                    }
                    response.status.isSuccess()
                } finally {
                    client.close()
                }
            }.getOrDefault(false)
        }
    }
}

/**
 * The last crash, on screen. Shown once at launch when a trace is waiting; sends it in the
 * background and says so. The trace itself stays readable so it can be photographed or typed
 * out even with no token and no network.
 */
class CrashScreen(
    sealedActivity: SealedLightActivity,
    private val trace: String,
    private val filesDir: File,
) : SimpleLightScreen<Unit>(sealedActivity) {

    @Composable
    override fun Content() {
        val colors by LightThemeController.colors.collectAsState()
        val scroll = rememberScrollState()
        var status by remember { mutableStateOf("SENDING TO LIGHT-REPORTS…") }

        LaunchedEffect(trace) {
            status = if (CrashLog.send(trace)) "SENT TO GI-OS/LIGHT-REPORTS" else "NOT SENT — READ IT HERE"
        }

        WheelKeys()
        WheelScroll(scroll)
        LightTheme(colors = colors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                LightTopBar(center = LightTopBarCenter.Text("News closed itself"))
                StatusLine(status)
                LightScrollView(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    scrollState = scroll,
                ) {
                    Column(
                        modifier = Modifier.padding(
                            start = SIDE_MARGIN_UNITS.gridUnitsAsDp(),
                            end = SIDE_MARGIN_UNITS.gridUnitsAsDp(),
                            bottom = 3f.gridUnitsAsDp(),
                        ),
                    ) {
                        LightText(
                            text = "The last time News ran it stopped with the error below. It has been " +
                                "kept so it can be fixed.",
                            variant = LightTextVariant.Paragraph,
                            lighten = true,
                            modifier = Modifier.padding(top = 1f.gridUnitsAsDp()),
                        )
                        LightText(
                            text = trace,
                            variant = LightTextVariant.Fine,
                            monospace = true,
                            modifier = Modifier.padding(top = 1.5f.gridUnitsAsDp()),
                        )
                    }
                }
                LightBottomBar(
                    items = listOf(
                        LightBarButton.Text("DISMISS", onClick = {
                            CrashLog.clear(filesDir)
                            goBack()
                        }),
                    ),
                )
            }
        }
    }
}
