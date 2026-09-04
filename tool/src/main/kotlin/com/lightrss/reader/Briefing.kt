package com.lightrss.reader

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The shapes behind the two home tabs, with no Android in them so they unit-test off-device.
 *
 * The **briefing** is today from the notebook followed by Kagi's edition, a category at a time.
 * The **timeline** is RSS and newsletters in one stream, cut into time buckets. Both are pure
 * functions of rows the database already produces.
 */
object Briefing {

    /** One followed Kagi category and its stories, in Kagi's ranking. */
    data class CategoryStories(val feedId: Long, val title: String, val stories: List<ArticleRow>)

    /** Rows arrive ordered by feed then rank; this only cuts them at the feed boundaries. */
    fun edition(rows: List<ArticleRow>): List<CategoryStories> {
        val out = ArrayList<CategoryStories>()
        var current: MutableList<ArticleRow>? = null
        for (row in rows) {
            val last = out.lastOrNull()
            if (last == null || last.feedId != row.article.feedId) {
                current = ArrayList()
                out.add(CategoryStories(row.article.feedId, row.feedTitle, current))
            }
            current!!.add(row)
        }
        return out
    }

    /** One line of the timeline: a bucket label, or a story. */
    sealed interface TimelineItem {
        val key: String

        /**
         * A bucket label. Buckets older than yesterday start folded — [count] stories behind
         * the header, none listed — so a week of feeds reads as today's paper with yesterday
         * underneath, not an archive. [count] is 0 for a bucket that is never folded.
         */
        data class Header(val label: String, val count: Int = 0, val folded: Boolean = false) : TimelineItem {
            override val key: String get() = "h:$label"
        }

        data class Story(val row: ArticleRow) : TimelineItem {
            override val key: String get() = row.article.id
        }
    }

    /**
     * Rows newest-first, with a header wherever the bucket changes. The journal day starts at
     * four in the morning, the same rule the notebook uses, so a 1 a.m. article belongs to the
     * evening it was read in and not to a morning nobody was awake for.
     */
    fun timeline(
        rows: List<ArticleRow>,
        now: Long,
        zone: ZoneId,
        opened: Set<String> = emptySet(),
    ): List<TimelineItem> {
        // Grouped by label, in order of first appearance, rather than cut wherever the label
        // changes. The two are the same for well-behaved feeds; they differ the moment a feed
        // stamps an article in the future (a timezone slip is enough), which used to put a
        // second THIS MORNING under THIS AFTERNOON — and a lazy list throws on the repeated key.
        val groups = LinkedHashMap<String, MutableList<ArticleRow>>()
        for (row in rows) {
            groups.getOrPut(bucket(row.article.publishedAt, now, zone)) { ArrayList() }.add(row)
        }
        val out = ArrayList<TimelineItem>(rows.size + groups.size)
        for ((label, stories) in groups) {
            val foldable = isFoldable(label)
            val folded = foldable && label !in opened
            out.add(TimelineItem.Header(label, count = if (foldable) stories.size else 0, folded = folded))
            if (!folded) stories.forEach { out.add(TimelineItem.Story(it)) }
        }
        return out
    }

    /** Today's three buckets and yesterday stay open; everything older folds. */
    fun isFoldable(label: String): Boolean = label !in OPEN_BUCKETS

    private val OPEN_BUCKETS = setOf("THIS MORNING", "THIS AFTERNOON", "THIS EVENING", "YESTERDAY")

    /**
     * When the edition on the phone was published: the top story's stamp, stepped back one
     * second per rank, so rank 1 is one second under the edition time. Null with no stories.
     */
    fun editionTime(edition: List<CategoryStories>): Long? =
        edition.flatMap { it.stories }.maxOfOrNull { it.article.publishedAt }?.plus(1_000L)

    /** `8:04 AM`. */
    fun clockLine(at: Long, zone: ZoneId): String =
        DateTimeFormatter.ofPattern("h:mm a", Locale.US).format(Instant.ofEpochMilli(at).atZone(zone)).uppercase(Locale.US)

    /** A Kagi story's sub-topic — `Antitrust` — from the `topic · location` line it was stored with. */
    fun topic(article: ArticleEntity): String =
        article.author.substringBefore(" · ").trim()

    fun bucket(publishedAt: Long, now: Long, zone: ZoneId): String {
        // A stamp from the future is a feed's clock being wrong, not news from tomorrow.
        val at = minOf(publishedAt, now)
        val today = journalDay(now, zone)
        val day = journalDay(at, zone)
        val daysAgo = today.toEpochDay() - day.toEpochDay()
        return when {
            daysAgo <= 0 -> {
                val hour = Instant.ofEpochMilli(at).atZone(zone).hour
                when {
                    hour in CUTOVER_HOUR until 12 -> "THIS MORNING"
                    hour in 12 until 17 -> "THIS AFTERNOON"
                    else -> "THIS EVENING"
                }
            }
            daysAgo == 1L -> "YESTERDAY"
            daysAgo < 7 -> day.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, Locale.US).uppercase(Locale.US)
            else -> DateTimeFormatter.ofPattern("MMM d", Locale.US).format(day).uppercase(Locale.US)
        }
    }

    private fun journalDay(at: Long, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(at).atZone(zone).minusHours(CUTOVER_HOUR.toLong()).toLocalDate()

    /**
     * The uppercase line over a timeline headline. A feed is named with its kind in front,
     * `RSS · THE VERGE`; a newsletter is `SENDER · LABEL`, because across a label every issue
     * carries the same label and what you recognise is who sent it.
     */
    fun sourceLine(row: ArticleRow): String {
        val sender = row.article.author.trim()
        val line = when {
            !NewsletterSync.isNewsletter(row.article.id) -> "RSS · ${row.feedTitle}"
            sender.isEmpty() || sender.equals(row.feedTitle, ignoreCase = true) -> row.feedTitle
            else -> "$sender · ${row.feedTitle}"
        }
        return line.uppercase(Locale.US)
    }

    /** `Thursday, Sep 3`. */
    fun dateLine(now: Long, zone: ZoneId): String =
        DateTimeFormatter.ofPattern("EEEE, MMM d", Locale.US).format(Instant.ofEpochMilli(now).atZone(zone))

    /** `68° and clear · Sunset 7:22 PM`, or null when the notebook has no weather for today. */
    fun weatherLine(weather: NotebookWeather?): String? {
        if (weather == null) return null
        val parts = ArrayList<String>(2)
        val tempF = weather.maxC?.let { Math.round(it * 9 / 5 + 32).toInt() }
        val sky = when (weather.kind) {
            "Clear" -> "clear"
            "Cloudy" -> "cloudy"
            "Fog" -> "foggy"
            "Rain" -> "rain"
            "Snow" -> "snow"
            "Storm" -> "storms"
            "Hail" -> "hail"
            else -> null
        }
        when {
            tempF != null && sky != null -> parts.add("$tempF° and $sky")
            tempF != null -> parts.add("$tempF°")
            sky != null -> parts.add(sky.replaceFirstChar { it.uppercase() })
        }
        if (weather.sunsetMinute >= 0) parts.add("Sunset ${clock(weather.sunsetMinute)}")
        return parts.joinToString(" · ").takeIf { it.isNotBlank() }
    }

    /** `9:30`, `19:00` — as the notebook stores them, 24-hour. */
    fun entryTime(entry: NotebookEntry): String =
        if (entry.allDay || entry.startMinute < 0) "—" else "%d:%02d".format(entry.startMinute / 60, entry.startMinute % 60)

    private fun clock(minute: Int): String {
        val h24 = (minute / 60) % 24
        val m = minute % 60
        val h12 = when (h24 % 12) { 0 -> 12; else -> h24 % 12 }
        return "%d:%02d %s".format(h12, m, if (h24 < 12) "AM" else "PM")
    }

    const val CUTOVER_HOUR = 4
}

/** One item on the notebook's page for today. */
data class NotebookEntry(
    val title: String,
    /** Clock minutes from midnight, or -1 for an all-day item. */
    val startMinute: Int,
    val endMinute: Int,
    val allDay: Boolean,
    /** `event`, `reminder`, `ticket`, `holiday`. */
    val kind: String,
)

/** Today's weather as the notebook has it. */
data class NotebookWeather(
    val code: Int,
    val kind: String,
    val maxC: Double?,
    val minC: Double?,
    val sunriseMinute: Int,
    val sunsetMinute: Int,
)

/** Today, from the notebook. Null when BrightNotebook is not installed or could not answer. */
data class NotebookDay(val entries: List<NotebookEntry>, val weather: NotebookWeather?)
