package com.lightrss.reader

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The newsletter rewriter, on the JVM.
 *
 * Deliberately the only part of the Gmail path with tests: everything else needs a mailbox, an
 * OAuth grant or a device. This is also the part most likely to be wrong in a way nobody notices
 * — a rewrite that eats an article reads as a short issue, not as a bug.
 *
 * Nothing here may call [NewsletterHtml.inlineCids]; that reaches `android.util.Base64`, which is
 * a stub on a unit-test classpath.
 */
class NewsletterHtmlTest {

    @Test
    fun `open-rate beacons are removed and real images survive`() {
        val html = """
            <html><body>
              <img src="https://track.example/o.gif" width="1" height="1">
              <img src="https://cdn.example/pixel.png" style="width:1px;height:1px">
              <img src="https://cdn.example/hero.jpg" width="600" height="320">
            </body></html>
        """.trimIndent()

        val out = NewsletterHtml.rewrite(html, RenderMode.DARK, loadImages = true)

        assertFalse("track.example" in out, "1x1 beacon survived")
        assertFalse("pixel.png" in out, "styled 1px beacon survived")
        assertContains(out, "hero.jpg")
    }

    @Test
    fun `line-height is not mistaken for a beacon`() {
        // The unanchored version of this regex reads the "height: 1" inside "line-height: 1.5"
        // and deletes a perfectly good image.
        val html = """<html><body><img src="a.jpg" style="line-height: 1.5;display:block"></body></html>"""

        val out = NewsletterHtml.rewrite(html, RenderMode.PAPER, loadImages = true)

        assertContains(out, "a.jpg")
    }

    @Test
    fun `fixed widths are unpinned so the copy can reflow`() {
        val html = """
            <html><body><table width="600" bgcolor="#ffffff"><tr><td width="600" align="center">
            Hello
            </td></tr></table></body></html>
        """.trimIndent()

        val out = NewsletterHtml.rewrite(html, RenderMode.DARK, loadImages = false)

        assertFalse("width=\"600\"" in out, "a fixed width survived")
        assertFalse("bgcolor" in out, "a table background survived")
    }

    @Test
    fun `dark mode strips inline colour but keeps other declarations`() {
        // Inline colour beats a stylesheet even with !important, so it has to come out of the
        // attribute rather than be overridden.
        val html = """<html><body><p style="color:#333;background:#fff;text-align:center">Hi</p></body></html>"""

        val out = NewsletterHtml.rewrite(html, RenderMode.DARK, loadImages = false)

        assertFalse("color:#333" in out)
        assertFalse("background:#fff" in out)
        assertContains(out, "text-align:center")
    }

    @Test
    fun `paper mode leaves the newsletter's own colour alone`() {
        val html = """<html><body><p style="color:#333">Hi</p></body></html>"""

        val out = NewsletterHtml.rewrite(html, RenderMode.PAPER, loadImages = false)

        assertContains(out, "color:#333")
    }

    @Test
    fun `a sponsor card is cut and marked`() {
        val html = """
            <html><body>
              <div><p>TOGETHER WITH ACME</p><p>Acme makes the best widgets. Buy some widgets.</p></div>
              <p>The actual issue starts here and runs on for a while so the size cap has room.</p>
            </body></html>
        """.trimIndent()

        val out = NewsletterHtml.rewrite(html, RenderMode.DARK, loadImages = false, blockAds = true)

        assertFalse("Acme makes the best widgets" in out, "the sponsor body survived")
        assertContains(out, "— ad —")
        assertContains(out, "The actual issue starts here")
    }

    @Test
    fun `sponsors are shown when the filter is off`() {
        val html = """<html><body><div><p>SPONSORED</p><p>Buy widgets.</p></div></body></html>"""

        val out = NewsletterHtml.rewrite(html, RenderMode.DARK, loadImages = false, blockAds = false)

        assertContains(out, "Buy widgets.")
    }

    @Test
    fun `the word sponsored inside prose is journalism, not a sponsor block`() {
        val prose = "The company sponsored the study, which is the whole problem with the study, " +
            "and the rest of this paragraph exists to prove the point at length."
        val html = "<html><body><p>$prose</p></body></html>"

        val out = NewsletterHtml.rewrite(html, RenderMode.DARK, loadImages = false, blockAds = true)

        assertContains(out, "which is the whole problem")
    }

    @Test
    fun `an oversized block is left alone rather than guessed at`() {
        // The size cap is the entire safety story: better a surviving ad than a swallowed issue.
        val body = "word ".repeat(400)
        val html = "<html><body><div><p>SPONSORED</p><p>$body</p></div></body></html>"

        val out = NewsletterHtml.rewrite(html, RenderMode.DARK, loadImages = false, blockAds = true)

        assertContains(out, "word word")
    }

    @Test
    fun `images off removes every image and frame`() {
        val html = """
            <html><body><img src="a.jpg"><iframe src="https://v.example"></iframe>
            <p>Text</p></body></html>
        """.trimIndent()

        val out = NewsletterHtml.rewrite(html, RenderMode.DARK, loadImages = false)

        // Assert on the elements and their sources, not on the words: the stylesheet this pass
        // injects mentions img, video and iframe by name in its own rules, so a bare substring
        // check finds them whether or not anything was removed.
        assertFalse("<iframe" in out)
        assertFalse("<img" in out)
        assertFalse("a.jpg" in out)
        assertFalse("v.example" in out)
        assertContains(out, "Text")
    }

    @Test
    fun `scripts and a base href never reach the WebView`() {
        // base[href] would re-anchor every relative URL and every in-document anchor against a
        // real host, which is exactly what the synthetic base exists to prevent.
        val html = """
            <html><head><base href="https://mail.example/"><script>alert(1)</script></head>
            <body><p>Text</p></body></html>
        """.trimIndent()

        val out = NewsletterHtml.rewrite(html, RenderMode.DARK, loadImages = false)

        assertFalse("alert(1)" in out)
        assertFalse("mail.example" in out)
    }

    @Test
    fun `a subject full of markup cannot rewrite the document`() {
        // The header is built through the DOM rather than by string concatenation, for this.
        val meta = ArticleMeta("<script>x</script> & co", "sender@example.com", "1 Aug")

        val out = NewsletterHtml.rewrite(
            "<html><body><p>Body</p></body></html>",
            RenderMode.DARK,
            loadImages = false,
            meta = meta,
        )

        assertFalse("<script>x</script>" in out, "the subject was injected as markup")
        assertContains(out, "&amp; co")
        assertContains(out, "sender@example.com")
    }

    @Test
    fun `cid images that missed the inlining pass are dropped, not left broken`() {
        val html = """<html><body><img src="cid:logo@example"><p>Body</p></body></html>"""

        val out = NewsletterHtml.rewrite(html, RenderMode.DARK, loadImages = true)

        assertFalse("cid:" in out)
        assertContains(out, "Body")
    }

    @Test
    fun `the text fallback keeps structure and link targets`() {
        val html = """
            <html><body><h1>Title</h1><p>One</p><ul><li>Two</li></ul>
            <p><a href="https://example.com/x">Read</a></p></body></html>
        """.trimIndent()

        val out = NewsletterHtml.toReadableText(html)

        assertContains(out, "• Two")
        assertContains(out, "Read <https://example.com/x>")
        assertTrue("\n\n" in out, "block structure collapsed to one line")
    }

    @Test
    fun `the text fallback puts the subject and sender on top`() {
        val meta = ArticleMeta("Weekly", "Money Stuff", "1 Aug")

        val out = NewsletterHtml.toReadableText("<html><body><p>Body</p></body></html>", meta)

        assertEquals("Weekly", out.lineSequence().first())
        assertContains(out, "Money Stuff · 1 Aug")
    }
}
