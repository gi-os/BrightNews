package com.lightrss.reader

import com.lightrss.reader.ReaderExtractor.hasContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
}
