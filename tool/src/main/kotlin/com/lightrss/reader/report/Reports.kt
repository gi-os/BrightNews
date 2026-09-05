package com.lightrss.reader.report

import android.os.Build
import com.lightrss.reader.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** How the app calls itself in an issue title, and the label the triage skill routes on. */
object ReportApp {
    const val NAME = "News"
    const val LABEL = "news"
}

/**
 * What went wrong, in the order the rows appear.
 *
 * Two labels because the two readers are different: `row` has to fit a list row on a 3.92"
 * panel, `label` is read weeks later in an issue title with no phone in front of you.
 */
enum class Symptom(val row: String, val label: String, val slug: String) {
    Crashed("CLOSED", "It closed itself", "crash"),
    Froze("FROZE", "It stopped responding", "freeze"),
    Wrong("LOOKS OFF", "Something looks wrong", "render"),
    Slow("SLOW", "It was very slow", "slow"),
    Other("OTHER", "Something else", "other"),
}

/** A report on its way out: exactly the three fields the issues API wants. */
data class Report(val title: String, val body: String, val labels: List<String>)

/**
 * Shake-to-report, from the phone to a GitHub issue.
 *
 * Reports queue on disk first and are posted afterwards, always — not as a fallback for being
 * offline. A phone that reports a freeze is by definition a phone that was just misbehaving, and
 * a report that exists only in flight is the one report guaranteed to be lost. The queue is also
 * why SEND can close the screen immediately: nothing the user sees depends on a socket.
 *
 * Ported from LightNews's `report/Reports`, minus the Context: a tool gets its files directory
 * from the SDK and its version from the build, so both are passed in rather than looked up. The
 * HTTP call stays on `HttpURLConnection` so the file is the same shape as the one in every other
 * Bright* app, and a fix to the queue lands everywhere the same way.
 */
object Reports {

    private const val DIR = "reports"
    private const val MAX_QUEUED = 20
    private const val TIMEOUT_MS = 45_000

    /** True when this build can actually send. False means reports pile up in the queue. */
    fun canSend(): Boolean = BuildConfig.REPORT_TOKEN.isNotBlank()

    /** Turn what the screen collected into an issue body. */
    fun compose(
        symptom: Symptom,
        note: String,
        screen: String,
        crash: String?,
        filesDir: File,
        packageName: String,
    ): Report {
        val trimmed = note.trim()
        // The note is the headline when there is one. A title reading "Something else" tells
        // you nothing three weeks later; "timeline empty after refresh" is the whole report.
        val headline = trimmed.takeIf { it.isNotEmpty() }?.let { first(it) } ?: symptom.label
        val body = buildString {
            appendLine("### What happened")
            appendLine()
            appendLine(symptom.label + (trimmed.takeIf { it.isNotEmpty() }?.let { " — $it" } ?: ""))
            appendLine()
            appendLine("### Where")
            appendLine()
            appendLine("On the `$screen` screen.")
            appendLine()
            appendLine("### Build")
            appendLine()
            appendLine("| | |")
            appendLine("|-|-|")
            appendLine("| App | ${ReportApp.NAME} ${BuildConfig.VERSION_NAME} |")
            appendLine("| Package | $packageName |")
            appendLine("| Android | ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) |")
            appendLine("| Device | ${Build.MANUFACTURER} ${Build.MODEL} |")
            appendLine("| Firmware | ${Build.DISPLAY} |")
            appendLine("| Reported | ${stamp()} |")
            appendLine("| Free space | ${megabytes(filesDir.freeSpace)} |")
            appendLine("| Heap | ${megabytes(usedHeap())} of ${megabytes(Runtime.getRuntime().maxMemory())} |")
            appendLine()
            appendLine("### Last crash")
            appendLine()
            if (crash.isNullOrBlank()) {
                appendLine("None — the app did not die, so this is a glitch and not a stack trace.")
            } else {
                appendLine("```")
                appendLine(crash.take(6_000))
                appendLine("```")
            }
        }
        return Report(
            title = "${ReportApp.LABEL}: $headline",
            body = body,
            labels = listOf(ReportApp.LABEL, symptom.slug),
        )
    }

    /** Write the report to disk, then try to send everything waiting. Queue first, always. */
    suspend fun submit(filesDir: File, report: Report): Unit = withContext(Dispatchers.IO) {
        enqueue(filesDir, report)
        flush(filesDir)
    }

    /** Post everything queued. Safe to call on launch; does nothing without a token. */
    suspend fun flush(filesDir: File): Unit = withContext(Dispatchers.IO) {
        if (!canSend()) return@withContext
        for (f in queued(filesDir)) {
            val text = runCatching { f.readText() }.getOrNull() ?: continue
            val json = runCatching { JSONObject(text) }.getOrNull() ?: run { f.delete(); return@withContext }
            if (post(json)) f.delete() else return@withContext
        }
    }

    // ---------------------------------------------------------------- queue

    private fun dir(filesDir: File) = File(filesDir, DIR).apply { mkdirs() }

    private fun queued(filesDir: File): List<File> =
        dir(filesDir).listFiles()?.filter { it.isFile }?.sortedBy { it.name } ?: emptyList()

    private fun enqueue(filesDir: File, report: Report) {
        val d = dir(filesDir)
        // A phone that has been failing offline for a week should not fill its own storage
        // with the evidence. Oldest goes first: the newest report is the one still relevant.
        val existing = queued(filesDir)
        if (existing.size >= MAX_QUEUED) {
            existing.take(existing.size - MAX_QUEUED + 1).forEach { it.delete() }
        }
        val payload = JSONObject()
            .put("title", report.title)
            .put("body", report.body)
            .put("labels", JSONArray().apply { report.labels.forEach { put(it) } })
        runCatching {
            File(d, "${System.currentTimeMillis()}-${(0..999).random()}.json")
                .writeText(payload.toString())
        }
    }

    // ---------------------------------------------------------------- transport

    /** @return true when the issue was created, or when it never can be and should be dropped. */
    private fun post(payload: JSONObject): Boolean {
        val url = URL("https://api.github.com/repos/${BuildConfig.REPORT_REPO}/issues")
        var conn: HttpURLConnection? = null
        return runCatching {
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                doOutput = true
                setRequestProperty("Authorization", "Bearer ${BuildConfig.REPORT_TOKEN}")
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("User-Agent", ReportApp.NAME)
            }
            conn!!.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn!!.responseCode
            // 4xx that is not rate limiting will never succeed — a bad token, a deleted repo,
            // a label that no longer exists. Dropping it is better than retrying it forever on
            // every launch.
            code in 200..299 || (code in 400..499 && code != 403 && code != 429)
        }.getOrDefault(false).also { runCatching { conn?.disconnect() } }
    }

    // ---------------------------------------------------------------- detail

    private fun first(text: String): String {
        val line = text.trim().lineSequence().firstOrNull().orEmpty().trim()
        return if (line.length <= 72) line else line.take(69).trimEnd() + "…"
    }

    private fun stamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())

    private fun megabytes(bytes: Long): String = "${bytes / 1_048_576} MB"

    private fun usedHeap(): Long =
        Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
}

/**
 * Where the app was when it went wrong.
 *
 * A single field rather than anything passed down: the shake arrives in a sensor callback that
 * has no view of the back stack, and a report is worth far more with "reader" on it than without.
 * Written from a screen's `willShow`, read when the report is composed, so it is volatile.
 */
object ReportContext {
    @Volatile
    var screen: String = "home"
}
