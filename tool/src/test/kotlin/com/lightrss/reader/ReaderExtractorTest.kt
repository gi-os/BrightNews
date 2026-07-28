package com.lightrss.reader

import com.lightrss.reader.ReaderExtractor.hasContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReaderExtractorTest {

    private val articlePage = """
        <!doctype html><html><head>
          <title>Long Piece — Example News</title>
          <meta property="og:title" content="A long piece about trains">
          <meta name="author" content="Ada Lovelace">
          <meta property="og:image" content="/img/lead.jpg">
        </head><body>
          <nav><ul><li><a href="/">Home</a></li></ul></nav>
          <div class="social-share"><a href="#">Share this everywhere you can think of</a></div>
          <article>
            <h1>A long piece about trains</h1>
            <p>The first paragraph runs long enough to look like real body copy rather than a caption.</p>
            <figure><img src="/img/platform.jpg" width="900" height="500"><figcaption>A platform</figcaption></figure>
            <p>The second paragraph also runs long, describing the platform and the people waiting.</p>
            <script>tracker('nope')</script>
          </article>
          <div class="related-stories"><p>You might also like this other thing, which is not the article.</p></div>
          <footer><p>Copyright someone, all rights reserved, with a mailing address and legal text.</p></footer>
        </body></html>
    """.trimIndent()

    @Test
    fun pullsTitleBylineAndOrderedBlocksOutOfAnArticlePage() {
        val page = ReaderExtractor.extract(articlePage, "https://news.example.com/trains/story")

        assertEquals("A long piece about trains", page.title)
        assertEquals("Ada Lovelace", page.byline)
        assertTrue(page.hasContent())
        assertEquals(
            listOf(
                "https://news.example.com/img/lead.jpg",
                "https://news.example.com/img/platform.jpg",
            ),
            page.blocks.filterIsInstance<ContentBlock.Image>().map { it.url },
        )
        assertTrue(page.blocks.first() is ContentBlock.Image, "lead image should come first")
    }

    @Test
    fun leavesOutNavigationPromosAndFooters() {
        val text = ReaderExtractor.extract(articlePage, "https://news.example.com/trains/story")
            .blocks
            .filterIsInstance<ContentBlock.Text>()
            .joinToString(" ") { it.text }

        assertFalse("Share this" in text, "share widget should be dropped")
        assertFalse("might also like" in text, "related stories should be dropped")
        assertFalse("all rights reserved" in text, "footer should be dropped")
        assertFalse("tracker" in text, "scripts should be dropped")
        assertTrue("first paragraph" in text)
        assertTrue("second paragraph" in text)
    }

    @Test
    fun doesNotRepeatTheHeadlineInTheBody() {
        val page = ReaderExtractor.extract(articlePage, "https://news.example.com/trains/story")
        val firstText = page.blocks.filterIsInstance<ContentBlock.Text>().first().text

        assertFalse(firstText.startsWith(page.title), "headline is already shown by the screen")
    }

    @Test
    fun handlesPagesWithNoArticleElement() {
        val page = ReaderExtractor.extract(
            """
                <html><head><title>Plain</title></head><body>
                <div id="menu"><p>Sections and links and other navigation chatter, not the story.</p></div>
                <div class="content">
                  <h1>Plain headline</h1>
                  <p>Body copy number one, long enough to be treated as the real thing here.</p>
                  <p>Body copy number two, also long enough to be treated as real article text.</p>
                </div>
                </body></html>
            """.trimIndent(),
            "https://example.org/plain",
        )

        assertEquals("Plain headline", page.title)
        assertTrue(page.hasContent())
        val text = page.blocks.filterIsInstance<ContentBlock.Text>().joinToString(" ") { it.text }
        assertTrue("Body copy number one" in text)
        assertFalse("navigation chatter" in text)
    }

    @Test
    fun reportsWhenThereIsNothingToRead() {
        val page = ReaderExtractor.extract(
            "<html><body><nav>menu</nav><div>hi</div></body></html>",
            "https://example.net/empty",
        )

        assertFalse(page.hasContent())
    }

    @Test
    fun findsTheCanonicalAddressBehindARedirector() {
        val html = """
            <html><head>
              <link rel="canonical" href="https://news.example.com/2026/07/the-real-story">
            </head><body><p>Short stub while the real page loads somewhere else entirely.</p></body></html>
        """.trimIndent()

        assertEquals(
            "https://news.example.com/2026/07/the-real-story",
            ReaderExtractor.canonicalUrl(html, "https://link.tracker.example/r/abc123"),
        )
    }

    @Test
    fun ignoresACanonicalThatPointsAtThePageItself() {
        val html = """
            <html><head><link rel="canonical" href="https://news.example.com/story/"></head><body></body></html>
        """.trimIndent()

        assertNull(ReaderExtractor.canonicalUrl(html, "https://news.example.com/story?utm_source=rss"))
    }

    @Test
    fun readsMetaRefreshAndAmpHops() {
        val refresh = """
            <html><head><meta http-equiv="refresh" content="0; url=https://example.com/final"></head></html>
        """.trimIndent()
        assertEquals(
            "https://example.com/final",
            ReaderExtractor.metaRefreshUrl(refresh, "https://short.example/x"),
        )

        val amp = """
            <html><head><link rel="amphtml" href="/story/amp"></head></html>
        """.trimIndent()
        assertEquals(
            "https://news.example.com/story/amp",
            ReaderExtractor.ampUrl(amp, "https://news.example.com/story"),
        )
    }

    @Test
    fun recognisesABotCheckInterstitial() {
        val html = """
            <html><head><title>Just a moment...</title></head><body>
              <h1>Thank you for your patience while we verify access.</h1>
              <p>Please enable JavaScript and cookies to continue to the article you requested.</p>
              <script src="/cdn-cgi/challenge-platform/h/b/orchestrate/chl_page/v1"></script>
            </body></html>
        """.trimIndent()
        val page = ReaderExtractor.extract(html, "https://paper.example.com/story")

        assertTrue(ReaderExtractor.isGate(html, page), "verification page should be flagged")
    }

    @Test
    fun recognisesASubscriptionWall() {
        val html = """
            <html><body><div class="paywall-inline">
              <h2>Subscribe to continue reading</h2>
              <p>Create an account to keep reading this story and get unlimited access today.</p>
            </div></body></html>
        """.trimIndent()
        val page = ReaderExtractor.extract(html, "https://paper.example.com/story")

        assertTrue(ReaderExtractor.isGate(html, page))
    }

    @Test
    fun doesNotFlagAnOrdinaryArticleAsAGate() {
        val page = ReaderExtractor.extract(articlePage, "https://news.example.com/trains/story")

        assertFalse(ReaderExtractor.isGate(articlePage, page))
    }
}
