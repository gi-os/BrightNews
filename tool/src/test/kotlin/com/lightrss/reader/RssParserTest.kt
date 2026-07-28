package com.lightrss.reader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RssParserTest {
    @Test
    fun parsesRssAndCleansArticleHtml() {
        val parsed = RssParser.parse(
            xml = """
                <?xml version="1.0"?>
                <rss version="2.0" xmlns:content="http://purl.org/rss/1.0/modules/content/">
                  <channel>
                    <title>Example &amp; Notes</title>
                    <link>https://example.com/</link>
                    <description>A focused feed</description>
                    <item>
                      <guid>post-1</guid>
                      <title>A &amp; B</title>
                      <link>/posts/one</link>
                      <pubDate>Tue, 21 Jul 2026 12:30:00 +0000</pubDate>
                      <description><![CDATA[<p>Hello <strong>reader</strong>.</p>]]></description>
                      <content:encoded><![CDATA[<p>First paragraph.</p><p>Second paragraph.</p>]]></content:encoded>
                    </item>
                  </channel>
                </rss>
            """.trimIndent(),
            sourceUrl = "https://example.com/feed.xml",
        )

        assertEquals("Example & Notes", parsed.title)
        assertEquals(1, parsed.items.size)
        assertEquals("A & B", parsed.items.single().title)
        assertEquals("https://example.com/posts/one", parsed.items.single().link)
        assertEquals("First paragraph.\n\nSecond paragraph.", parsed.items.single().content)
    }

    @Test
    fun parsesAtomLinksAuthorsAndIsoDates() {
        val parsed = RssParser.parse(
            xml = """
                <feed xmlns="http://www.w3.org/2005/Atom">
                  <title>Atom Example</title>
                  <link href="https://example.org/" rel="alternate"/>
                  <entry>
                    <id>tag:example.org,2026:1</id>
                    <title>One small post</title>
                    <link href="/one"/>
                    <author><name>Ada</name></author>
                    <updated>2026-07-21T14:00:00Z</updated>
                    <content type="xhtml"><div xmlns="http://www.w3.org/1999/xhtml"><p>Readable <strong>text</strong></p></div></content>
                  </entry>
                </feed>
            """.trimIndent(),
            sourceUrl = "https://example.org/atom.xml",
        )

        val article = parsed.items.single()
        assertEquals("Ada", article.author)
        assertEquals("https://example.org/one", article.link)
        assertEquals("Readable text", article.content)
        assertTrue(article.publishedAt > 0)
    }

    @Test
    fun discoversRelativeFeedFromWebPage() {
        val url = RssParser.discoverFeedUrl(
            """<html><head><link rel="alternate" type="application/rss+xml" href="/news.xml"></head></html>""",
            "https://example.net/posts/index.html",
        )
        assertEquals("https://example.net/news.xml", url)
    }

    @Test
    fun stableIdsAreFeedScoped() {
        val first = RssParser.stableArticleId("https://a.example/feed", "42", "", "Post")
        val same = RssParser.stableArticleId("https://a.example/feed", "42", "", "Post")
        val otherFeed = RssParser.stableArticleId("https://b.example/feed", "42", "", "Post")
        assertEquals(first, same)
        assertNotEquals(first, otherFeed)
    }

    @Test
    fun cleansInlineMarkupWithoutJoiningWords() {
        val text = RssParser.cleanHtml("<p>Optical:<strong>Brian Brennan</strong></p>")
        assertEquals("Optical: Brian Brennan", text)
    }

    @Test
    fun formatsReaderSourceAsAQuietHostLabel() {
        assertEquals("EXAMPLE.COM", sourceHost("https://www.example.com/posts/one?ref=rss"))
        assertEquals("", sourceHost("not a url"))
    }

    @Test
    fun rejectsIncompleteFeedAddressesBeforeNetworking() {
        val error = assertFailsWith<IllegalArgumentException> { RssApi.normalizeUrl("abc") }
        assertEquals("Enter a complete website or feed address.", error.message)
        assertEquals("https://example.com/feed", RssApi.normalizeUrl("example.com/feed"))
    }

    @Test
    fun extractsEnclosureImageAndInlineBlocks() {
        val parsed = RssParser.parse(
            xml = """
                <?xml version="1.0"?>
                <rss version="2.0" xmlns:content="http://purl.org/rss/1.0/modules/content/">
                  <channel>
                    <title>Pictures</title>
                    <link>https://example.com/</link>
                    <item>
                      <guid>with-images</guid>
                      <title>Has pictures</title>
                      <link>/posts/one</link>
                      <enclosure url="https://cdn.example.com/hero.jpg" type="image/jpeg" length="2048"/>
                      <content:encoded><![CDATA[<p>Intro.</p><img src="/img/a.png" width="800" height="400"/><p>After.</p>]]></content:encoded>
                    </item>
                  </channel>
                </rss>
            """.trimIndent(),
            sourceUrl = "https://example.com/feed.xml",
        )

        val item = parsed.items.single()
        assertEquals("https://cdn.example.com/hero.jpg", item.imageUrl)
        assertEquals(
            listOf(
                ContentBlock.Text("Intro."),
                ContentBlock.Image("https://example.com/img/a.png"),
                ContentBlock.Text("After."),
            ),
            item.blocks,
        )
        assertEquals("Intro.\n\nAfter.", item.content)
    }

    @Test
    fun usesMediaThumbnailAndResolvesProtocolRelativeUrls() {
        val parsed = RssParser.parse(
            xml = """
                <?xml version="1.0"?>
                <rss version="2.0" xmlns:media="http://search.yahoo.com/mrss/">
                  <channel>
                    <title>Media</title>
                    <link>https://example.com/</link>
                    <item>
                      <guid>thumb</guid>
                      <title>Thumbnail only</title>
                      <link>https://example.com/two</link>
                      <media:thumbnail url="//cdn.example.com/thumb.jpg"/>
                      <description><![CDATA[<p>Text only.</p>]]></description>
                    </item>
                  </channel>
                </rss>
            """.trimIndent(),
            sourceUrl = "https://example.com/feed.xml",
        )

        val item = parsed.items.single()
        assertEquals("https://cdn.example.com/thumb.jpg", item.imageUrl)
        assertTrue(item.blocks.isEmpty(), "text-only articles should not carry blocks")
    }

    @Test
    fun skipsTrackingPixelsAndDataUris() {
        val html = """
            <p>Story.</p>
            <img src="https://feeds.feedburner.com/~ff/pixel.gif" width="1" height="1"/>
            <img src="data:image/png;base64,iVBORw0KGgo="/>
            <img src="https://cdn.example.com/real.jpg"/>
        """.trimIndent()

        assertEquals(
            listOf("https://cdn.example.com/real.jpg"),
            RssParser.imagesInHtml(html),
        )
    }

    @Test
    fun contentBlocksSurviveEncodingRoundTrip() {
        val blocks = listOf(
            ContentBlock.Text("Line one\nLine two\twith a tab and a \\ backslash"),
            ContentBlock.Image("https://cdn.example.com/a.jpg"),
            ContentBlock.Text("Closing paragraph."),
        )

        assertEquals(blocks, ContentBlocks.decode(ContentBlocks.encode(blocks)))
        assertTrue(ContentBlocks.decode("").isEmpty())
    }

    @Test
    fun readsFeedAddressesOutOfScannedQrPayloads() {
        assertEquals("https://example.com/feed.xml", RssParser.feedUrlFromScan(" https://example.com/feed.xml "))
        assertEquals("https://example.com/rss", RssParser.feedUrlFromScan("feed://example.com/rss"))
        assertEquals("example.com/rss", RssParser.feedUrlFromScan("example.com/rss"))
        assertEquals(
            "https://blog.example.com/atom.xml",
            RssParser.feedUrlFromScan("Subscribe: https://blog.example.com/atom.xml thanks!"),
        )
        assertFailsWith<IllegalArgumentException> { RssParser.feedUrlFromScan("   ") }
        assertFailsWith<IllegalArgumentException> { RssParser.feedUrlFromScan("WIFI:S=cafe;T=WPA;P=hunter2;; and more") }
    }
}
