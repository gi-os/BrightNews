package com.lightrss.reader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KagiTest {

    private val index = """
        {"timestamp": 1788436907, "categories": [
          {"name": "World", "file": "world.json"},
          {"name": "USA", "file": "usa.json"},
          {"name": "Business", "file": "business.json"},
          {"name": "Technology", "file": "tech.json"},
          {"name": "Science", "file": "science.json"},
          {"name": "Sports", "file": "sports.json"},
          {"name": "Gaming", "file": "gaming.json"},
          {"name": "Bay Area", "file": "bay.json"},
          {"name": "Germany | Berlin", "file": "germany_|_berlin.json"},
          {"name": "USA | Austin, TX", "file": "usa_|_austin,_tx.json"},
          {"name": "AI", "file": "ai.json"},
          {"name": "Apple", "file": "apple.json"},
          {"name": "France", "file": "france.json"},
          {"name": "Switzerland (DE)", "file": "switzerland_(de).json"},
          {"name": "Healthcare | USA", "file": "healthcare_|_usa.json"},
          {"name": "OnThisDay", "file": "onthisday.json"}
        ], "supported_languages": ["en"]}
    """.trimIndent()

    @Test
    fun `index parses and shelves`() {
        val categories = Kagi.parseIndex(index)
        assertEquals(16, categories.size)
        val shelved = Kagi.shelve(categories).associateBy { it.category.name }

        assertEquals(Kagi.Group.CORE, shelved.getValue("World").group)
        assertEquals(Kagi.Group.CORE, shelved.getValue("Bay Area").group)
        assertEquals(Kagi.Group.CORE, shelved.getValue("OnThisDay").group)
        assertEquals(Kagi.Group.PLACES, shelved.getValue("Germany | Berlin").group)
        assertEquals("Germany", shelved.getValue("Germany | Berlin").parent)
        assertEquals("Berlin", shelved.getValue("Germany | Berlin").shortName)
        assertEquals("USA", shelved.getValue("USA | Austin, TX").parent)
        assertEquals(Kagi.COUNTRIES_PARENT, shelved.getValue("France").parent)
        assertEquals(Kagi.COUNTRIES_PARENT, shelved.getValue("Switzerland (DE)").parent)
        assertEquals(Kagi.Group.TOPICS, shelved.getValue("AI").group)
        // A proper noun is not a place, and a topic qualified by a place is still a topic.
        assertEquals(Kagi.Group.TOPICS, shelved.getValue("Apple").group)
        assertEquals(Kagi.Group.TOPICS, shelved.getValue("Healthcare | USA").group)
        assertEquals(null, shelved.getValue("Healthcare | USA").parent)
    }

    @Test
    fun `category file names are encoded as one path segment`() {
        assertEquals("https://news.kagi.com/world.json", Kagi.categoryUrl("world.json"))
        assertEquals("https://news.kagi.com/germany_%7C_berlin.json", Kagi.categoryUrl("germany_|_berlin.json"))
        assertEquals("https://news.kagi.com/usa_%7C_austin%2C_tx.json", Kagi.categoryUrl("usa_|_austin,_tx.json"))
        assertEquals("https://news.kagi.com/switzerland_%28de%29.json", Kagi.categoryUrl("switzerland_(de).json"))
        assertEquals(
            "https://news.kagi.com/germany_%7C_baden-w%C3%BCrttemberg.json",
            Kagi.categoryUrl("germany_|_baden-württemberg.json"),
        )
    }

    private val edition = """
        {"category": "Technology", "timestamp": 1788425064, "read": 2659, "clusters": [
          {"cluster_number": 1, "unique_domains": 47, "number_of_titles": 47,
           "category": "Antitrust", "title": "U.S. judge rejects forced sale of Google AdX",
           "short_summary": "A judge refused to order a sale [engadget.com#1]. Rivals rose [businessinsider.com#1][cnbc.com#2].",
           "did_you_know": "", "talking_points": ["Fees: reports said 20% [x.com#1].", ""],
           "quote": "We're very pleased", "quote_author": "Lee-Anne Mulholland", "quote_attribution": "Engadget",
           "location": "Alexandria, VA, United States",
           "perspectives": [{"text": "DOJ: could not be trusted.", "sources": []}],
           "timeline": [{"date": "2020", "content": "Case filed [tc.com#1]", "date_iso": "2020"}],
           "historical_background": null,
           "primary_image": {"url": "https://kagiproxy.com/img/abc"},
           "articles": [
             {"title": "Google dodges breakup", "link": "https://www.politico.com/x", "domain": "politico.com", "date": "2026-09-02"},
             {"title": "Second from same site", "link": "https://www.politico.com/y", "domain": "politico.com"},
             {"title": "Engadget take", "link": "https://www.engadget.com/z", "domain": "engadget.com"}
           ]},
          {"cluster_number": 2, "title": "Second story", "short_summary": "Text.", "articles": []}
        ]}
    """.trimIndent()

    @Test
    fun `edition parses with nulls and blanks tolerated`() {
        val parsed = Kagi.parseEdition(edition)
        assertEquals("Technology", parsed.category)
        assertEquals(1788425064_000L, parsed.publishedAt)
        assertEquals(2, parsed.clusters.size)

        val first = parsed.clusters[0]
        assertEquals(1, first.rank)
        assertEquals("Antitrust", first.topic)
        assertEquals(listOf("Fees: reports said 20% [x.com#1]."), first.highlights)
        assertEquals("Lee-Anne Mulholland, Engadget", first.quoteAuthor)
        assertEquals(listOf("2020 — Case filed"), first.timeline)
        assertEquals(3, first.sources.size)
        assertEquals(47, first.sourceCount)

        // Rank order falls out of the newest-first sort.
        assertTrue(Kagi.storyPublishedAt(parsed, parsed.clusters[0]) > Kagi.storyPublishedAt(parsed, parsed.clusters[1]))
    }

    @Test
    fun `citations are stripped from prose`() {
        assertEquals(
            "A judge refused to order a sale. Rivals rose.",
            Kagi.stripCitations("A judge refused to order a sale [engadget.com#1]. Rivals rose [businessinsider.com#1][cnbc.com#2]."),
        )
        assertEquals("Both gone.", Kagi.stripCitations("Both [a.com#1, b.com#2] gone."))
    }

    @Test
    fun `story blocks carry the sections and dedupe sources by domain`() {
        val cluster = Kagi.parseEdition(edition).clusters[0]
        val blocks = Kagi.blocks(cluster)

        assertEquals(ContentBlock.Image("https://kagiproxy.com/img/abc"), blocks.first())
        val headings = blocks.filterIsInstance<ContentBlock.Heading>().map { it.text }
        assertEquals(listOf("Highlights", "Quote", "Perspectives", "Timeline", "Sources (47)"), headings)
        val links = blocks.filterIsInstance<ContentBlock.Link>()
        assertEquals(listOf("politico.com", "engadget.com"), links.map { java.net.URI(it.url).host.removePrefix("www.") })

        // Sections survive the trip through the database column.
        val decoded = ContentBlocks.decode(ContentBlocks.encode(blocks))
        assertEquals(blocks, decoded)
    }

    @Test
    fun `the same headline is the same story wherever it lands`() {
        assertEquals(Kagi.storyGuid("Judge rejects forced sale of Google AdX"), Kagi.storyGuid("judge rejects forced sale of google adx!"))
        assertTrue(Kagi.storyGuid("A").startsWith("kagi:"))
    }
}
