package com.lightrss.reader

import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BriefingTest {

    private val zone: ZoneId = ZoneId.of("America/New_York")
    private fun at(hour: Int, minute: Int = 0, dayOffset: Long = 0): Long =
        ZonedDateTime.of(2026, 9, 3, hour, minute, 0, 0, zone).plusDays(dayOffset).toInstant().toEpochMilli()
    private val now = at(14, 30) // Thursday Sep 3, 2:30 pm

    private fun row(id: String, feedId: Long, feedTitle: String, publishedAt: Long, author: String = "") = ArticleRow(
        article = ArticleEntity(
            id = id, feedId = feedId, guid = id, title = "T $id", link = "https://x/$id",
            author = author, publishedAt = publishedAt,
        ),
        feedTitle = feedTitle,
    )

    @Test
    fun `buckets follow the four o clock journal day`() {
        assertEquals("THIS MORNING", Briefing.bucket(at(8), now, zone))
        assertEquals("THIS AFTERNOON", Briefing.bucket(at(13), now, zone))
        assertEquals("THIS EVENING", Briefing.bucket(at(19), now, zone))
        // One in the morning belongs to the evening before, not to a morning nobody saw.
        assertEquals("THIS EVENING", Briefing.bucket(at(1, dayOffset = 1), now, zone))
        assertEquals("YESTERDAY", Briefing.bucket(at(9, dayOffset = -1), now, zone))
        assertEquals("MONDAY", Briefing.bucket(at(9, dayOffset = -3), now, zone))
        assertEquals("AUG 20", Briefing.bucket(at(9, dayOffset = -14), now, zone))
    }

    @Test
    fun `a header lands wherever the bucket changes`() {
        val rows = listOf(
            row("a", 1, "The Verge", at(14)),
            row("b", 1, "The Verge", at(9)),
            row("c", 2, "AP", at(8)),
            row("d", 2, "AP", at(20, dayOffset = -1)),
        )
        val labels = Briefing.timeline(rows, now, zone).map {
            when (it) {
                is Briefing.TimelineItem.Header -> "#" + it.label
                is Briefing.TimelineItem.Story -> it.row.article.id
            }
        }
        assertEquals(listOf("#THIS AFTERNOON", "a", "#THIS MORNING", "b", "c", "#YESTERDAY", "d"), labels)
    }

    @Test
    fun `older buckets fold to a counted header until opened`() {
        val rows = listOf(
            row("a", 1, "AP", at(9)),
            row("b", 1, "AP", at(9, dayOffset = -1)),
            row("c", 1, "AP", at(9, dayOffset = -3)),
            row("d", 1, "AP", at(10, dayOffset = -3)),
        )
        val folded = Briefing.timeline(rows, now, zone)
        val monday = folded.filterIsInstance<Briefing.TimelineItem.Header>().single { it.label == "MONDAY" }
        assertEquals(2, monday.count)
        assertEquals(true, monday.folded)
        assertEquals(listOf("a", "b"), folded.filterIsInstance<Briefing.TimelineItem.Story>().map { it.row.article.id })
        // Today's buckets never fold and carry no count.
        assertEquals(0, folded.filterIsInstance<Briefing.TimelineItem.Header>().first { it.label == "THIS MORNING" }.count)

        val opened = Briefing.timeline(rows, now, zone, opened = setOf("MONDAY"))
        assertEquals(listOf("a", "b", "c", "d"), opened.filterIsInstance<Briefing.TimelineItem.Story>().map { it.row.article.id })
    }

    @Test
    fun `edition time and topic`() {
        val edition = Briefing.edition(listOf(row("w1", 10, "World", at(8, 4) - 1_000), row("w2", 10, "World", at(8, 4) - 2_000)))
        assertEquals(at(8, 4), Briefing.editionTime(edition))
        assertEquals("8:04 AM", Briefing.clockLine(at(8, 4), zone))
        assertNull(Briefing.editionTime(emptyList()))
        assertEquals("Antitrust", Briefing.topic(row("k", 1, "Tech", now, author = "Antitrust · Alexandria, VA").article))
        assertEquals("", Briefing.topic(row("k", 1, "Tech", now).article))
    }

    @Test
    fun `the edition is cut at feed boundaries in the order given`() {
        val rows = listOf(
            row("w1", 10, "World", 300), row("w2", 10, "World", 200),
            row("t1", 11, "Technology", 300),
        )
        val edition = Briefing.edition(rows)
        assertEquals(listOf("World", "Technology"), edition.map { it.title })
        assertEquals(listOf("w1", "w2"), edition[0].stories.map { it.article.id })
    }

    @Test
    fun `source lines name the kind for feeds and the sender for newsletters`() {
        assertEquals("RSS · THE VERGE", Briefing.sourceLine(row("a", 1, "The Verge", now, author = "Nilay")))
        assertEquals("BEN THOMPSON · STRATECHERY", Briefing.sourceLine(row("gmail:1", 2, "Stratechery", now, author = "Ben Thompson")))
        assertEquals("STRATECHERY", Briefing.sourceLine(row("gmail:2", 2, "Stratechery", now, author = "stratechery")))
    }

    @Test
    fun `weather and calendar lines`() {
        assertEquals(
            "68° and clear · Sunset 7:22 PM",
            Briefing.weatherLine(NotebookWeather(0, "Clear", 20.0, 12.0, 390, 19 * 60 + 22)),
        )
        assertEquals("Rain", Briefing.weatherLine(NotebookWeather(61, "Rain", null, null, -1, -1)))
        assertNull(Briefing.weatherLine(null))
        assertEquals("9:30", Briefing.entryTime(NotebookEntry("Dentist", 570, 600, false, "event")))
        assertEquals("—", Briefing.entryTime(NotebookEntry("Labor Day", -1, -1, true, "holiday")))
        assertEquals("Thursday, Sep 3", Briefing.dateLine(now, zone))
    }
}
