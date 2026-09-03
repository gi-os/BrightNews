package com.lightrss.reader

import android.content.Context // light-sdk-allow: reading BrightNotebook's exported provider needs a ContentResolver
import android.net.Uri

/**
 * Today's page, read out of BrightNotebook.
 *
 * BrightNotebook (`com.gios.lightnotebook`) is a separate sideloaded app and the owner of the
 * day — the calendar, the imported feeds, the tickets, the weather it archives overnight. It
 * serves the current journal day over a read-only provider, and this is the only place that
 * reads it. The provider's authority is declared in `lighttool.toml` (`queryProviders`), which
 * is what makes it visible under Android 11's package filtering.
 *
 * Every failure is `null` — Notebook not installed, an older Notebook without these paths, a
 * database mid-upgrade — and the briefing then shows the day without a calendar and without
 * weather. A day with nothing on it is a fine answer; an error dialog over the news is not.
 *
 * The `Context` import and the resolver access are blocked by the SDK build policy and marked
 * exempt line by line, the way `ColorMode.kt` does for the daltonizer.
 */
object NotebookBridge {

    fun read(context: Context): NotebookDay? {
        val resolver = context.contentResolver // light-sdk-allow: the notebook's provider is the only source of the day
        val entries = runCatching {
            resolver.query(DAY_URI, null, null, null, null)?.use { cursor ->
                // OrThrow: a missing column is an older notebook, and the runCatching above
                // turns that into "no day", which is the right answer.
                val title = cursor.getColumnIndexOrThrow("title")
                val start = cursor.getColumnIndexOrThrow("startMinute")
                val end = cursor.getColumnIndexOrThrow("endMinute")
                val allDay = cursor.getColumnIndexOrThrow("allDay")
                val kind = cursor.getColumnIndexOrThrow("kind")
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            NotebookEntry(
                                title = cursor.getString(title).orEmpty(),
                                startMinute = cursor.getInt(start),
                                endMinute = cursor.getInt(end),
                                allDay = cursor.getInt(allDay) == 1,
                                kind = cursor.getString(kind).orEmpty(),
                            ),
                        )
                    }
                }
            }
        }.getOrNull() ?: return null

        val weather = runCatching {
            resolver.query(WEATHER_URI, null, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                fun double(name: String): Double? {
                    val index = cursor.getColumnIndexOrThrow(name)
                    return if (cursor.isNull(index)) null else cursor.getDouble(index)
                }
                NotebookWeather(
                    code = cursor.getInt(cursor.getColumnIndexOrThrow("code")),
                    kind = cursor.getString(cursor.getColumnIndexOrThrow("kind")).orEmpty(),
                    maxC = double("maxC"),
                    minC = double("minC"),
                    sunriseMinute = cursor.getInt(cursor.getColumnIndexOrThrow("sunriseMinute")),
                    sunsetMinute = cursor.getInt(cursor.getColumnIndexOrThrow("sunsetMinute")),
                )
            }
        }.getOrNull()

        return NotebookDay(entries, weather)
    }

    private const val AUTHORITY = "com.gios.lightnotebook.nextup"
    private val DAY_URI: Uri = Uri.parse("content://$AUTHORITY/day")
    private val WEATHER_URI: Uri = Uri.parse("content://$AUTHORITY/weather")
}
