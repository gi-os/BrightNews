package com.lightrss.reader

import java.net.URI

/** A web page reduced to the part worth reading. */
data class ReaderPage(
    val title: String,
    val byline: String,
    val blocks: List<ContentBlock>,
)

/**
 * Turns an article page into text and images, with no browser involved.
 *
 * There is no DOM here, so this does not try to score a tree of candidate containers the way a
 * desktop reader mode would. It leans on a simpler property that holds for almost every article
 * page: the body copy is the longest run of substantial paragraphs on the page. Navigation,
 * promos, and comment widgets are stripped first, the run is located, and everything inside it
 * is converted in document order.
 */
object ReaderExtractor {

    /**
     * The address the page says it really lives at. Feeds often link through a redirector or to a
     * tracking variant of a story, and those pages point back at the original with a canonical
     * link. Returns null when the page agrees with [pageUrl] or declares nothing.
     */
    fun canonicalUrl(html: String, pageUrl: String): String? {
        val declared = linkHref(html, "canonical")
            ?: metaContent(html, "og:url")
            ?: return null
        val resolved = runCatching { URI(pageUrl).resolve(declared).toString() }.getOrNull() ?: return null
        if (!resolved.startsWith("http", ignoreCase = true)) return null
        return resolved.takeIf { differentPage(it, pageUrl) }
    }

    /** An AMP copy of the page, which is usually lighter and less hostile to a text reader. */
    fun ampUrl(html: String, pageUrl: String): String? {
        val declared = linkHref(html, "amphtml") ?: return null
        val resolved = runCatching { URI(pageUrl).resolve(declared).toString() }.getOrNull() ?: return null
        return resolved.takeIf { it.startsWith("http", ignoreCase = true) && differentPage(it, pageUrl) }
    }

    /** Follows `<meta http-equiv="refresh">` hops, which some redirectors use instead of a 302. */
    fun metaRefreshUrl(html: String, pageUrl: String): String? {
        val tag = Regex("""(?is)<meta\b[^>]*\bhttp-equiv\s*=\s*["']refresh["'][^>]*>""")
            .find(html)
            ?.value
            ?: return null
        val content = Regex("""(?is)\bcontent\s*=\s*["']([^"']*)["']""").find(tag)?.groupValues?.get(1)
            ?: return null
        val target = Regex("""(?i)url\s*=\s*(.+)$""").find(content.trim())?.groupValues?.get(1)?.trim('\'', '"', ' ')
            ?: return null
        val resolved = runCatching { URI(pageUrl).resolve(target).toString() }.getOrNull() ?: return null
        return resolved.takeIf { it.startsWith("http", ignoreCase = true) && differentPage(it, pageUrl) }
    }

    private fun linkHref(html: String, rel: String): String? {
        val tag = Regex("""(?is)<link\b[^>]*\brel\s*=\s*["'][^"']*\b${Regex.escape(rel)}\b[^"']*["'][^>]*>""")
            .find(html)
            ?.value
            ?: return null
        return Regex("""(?is)\bhref\s*=\s*["']([^"']+)["']""").find(tag)
            ?.groupValues
            ?.get(1)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    /** Compares two addresses ignoring the trailing slash, the query, and the fragment. */
    private fun differentPage(candidate: String, current: String): Boolean {
        fun key(value: String): String = runCatching {
            val uri = URI(value)
            (uri.host.orEmpty().removePrefix("www.") + uri.path.orEmpty().trimEnd('/')).lowercase()
        }.getOrDefault(value)
        return key(candidate) != key(current)
    }

    fun extract(html: String, pageUrl: String): ReaderPage {
        val stripped = stripChrome(html)
        val body = mainRegion(stripped)
        val resolve: (String) -> String = { candidate ->
            runCatching { URI(pageUrl).resolve(candidate).toString() }.getOrDefault(candidate)
        }

        val title = pageTitle(html)
        val blocks = RssParser.contentBlocks(body, resolve, imagesRequired = false)
            .filter { block -> block !is ContentBlock.Text || block.text.length > 1 }
            .let { dropRepeatedTitle(it, title) }

        val lead = metaContent(html, "og:image")
            ?.let(resolve)
            ?.takeIf { RssParser.isUsableImageUrl(it) }
        val withLead = if (lead != null && blocks.none { it is ContentBlock.Image && it.url == lead }) {
            listOf(ContentBlock.Image(lead)) + blocks
        } else {
            blocks
        }

        return ReaderPage(
            title = title,
            byline = pageByline(html),
            blocks = withLead,
        )
    }

    /** True when the page gave us enough to be worth showing. */
    fun ReaderPage.hasContent(): Boolean =
        blocks.filterIsInstance<ContentBlock.Text>().sumOf { it.text.length } >= MIN_ARTICLE_CHARS

    /** The page heading is shown by the screen, so avoid printing it twice. */
    private fun dropRepeatedTitle(blocks: List<ContentBlock>, title: String): List<ContentBlock> {
        if (title.isBlank()) return blocks
        val first = blocks.firstOrNull() as? ContentBlock.Text ?: return blocks
        val text = first.text.trimStart()
        if (!text.startsWith(title)) return blocks
        val remainder = text.removePrefix(title).trimStart()
        return if (remainder.isBlank()) blocks.drop(1) else listOf(ContentBlock.Text(remainder)) + blocks.drop(1)
    }

    private fun stripChrome(html: String): String {
        var out = html
        for (tag in DISCARDED_TAGS) {
            out = out.replace(Regex("""(?is)<$tag\b[^>]*>.*?</$tag>"""), " ")
        }
        // Elements whose class or id advertises that they are not the article.
        out = out.replace(
            Regex(
                """(?is)<(div|section|ul|ol|span|p)\b[^>]*\b(?:class|id)\s*=\s*["'][^"']*""" +
                    """(?:$JUNK_WORDS)[^"']*["'][^>]*>.{0,4000}?</\1>""",
            ),
            " ",
        )
        return out
    }

    /**
     * The span running from the first substantial paragraph to the last one. Falls back to an
     * `<article>` element, and then to the whole document, when paragraphs are not marked up.
     */
    private fun mainRegion(html: String): String {
        val article = betweenTags(html, "article")
        val source = if (article != null && Regex("""(?is)<p\b""").findAll(article).count() >= 2) {
            article
        } else {
            html
        }

        val paragraphs = Regex("""(?is)<p\b[^>]*>(.*?)</p>""").findAll(source).toList()
        val substantial = paragraphs.filter { RssParser.cleanHtml(it.groupValues[1]).length >= MIN_PARAGRAPH_CHARS }
        val chosen = substantial.ifEmpty {
            paragraphs.filter { RssParser.cleanHtml(it.groupValues[1]).length >= SHORT_PARAGRAPH_CHARS }
        }
        if (chosen.isEmpty()) return article ?: source

        val start = chosen.first().range.first
        val end = chosen.last().range.last
        // Reach backwards for a heading or lead image sitting just above the first paragraph,
        // but never past the start of the body.
        val bodyStart = source.lastIndexOf("<body", start, ignoreCase = true).coerceAtLeast(0)
        val prelude = maxOf((start - PRELUDE_CHARS).coerceAtLeast(0), bodyStart)
        return source.substring(prelude, (end + 1).coerceAtMost(source.length))
    }

    private fun betweenTags(html: String, tag: String): String? {
        val open = Regex("""(?is)<$tag\b[^>]*>""").find(html) ?: return null
        val close = html.lastIndexOf("</$tag>", ignoreCase = true)
        if (close <= open.range.last) return null
        return html.substring(open.range.last + 1, close)
    }

    private fun pageTitle(html: String): String {
        val candidates = listOfNotNull(
            metaContent(html, "og:title"),
            metaContent(html, "twitter:title"),
            Regex("""(?is)<h1\b[^>]*>(.*?)</h1>""").find(html)?.groupValues?.get(1),
            Regex("""(?is)<title\b[^>]*>(.*?)</title>""").find(html)?.groupValues?.get(1),
        )
        return candidates
            .map { RssParser.cleanHtml(it) }
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
            .take(MAX_TITLE_CHARS)
    }

    private fun pageByline(html: String): String {
        val candidates = listOfNotNull(
            metaContent(html, "author"),
            metaContent(html, "article:author"),
            Regex("""(?is)<[^>]+\brel\s*=\s*["']author["'][^>]*>(.*?)</""").find(html)?.groupValues?.get(1),
        )
        return candidates
            .map { RssParser.cleanHtml(it) }
            .firstOrNull { it.isNotBlank() && !it.startsWith("http") }
            .orEmpty()
            .take(MAX_BYLINE_CHARS)
    }

    private fun metaContent(html: String, name: String): String? {
        val pattern = Regex(
            """(?is)<meta\b[^>]*\b(?:property|name)\s*=\s*["']${Regex.escape(name)}["'][^>]*>""",
        )
        val tag = pattern.find(html)?.value ?: return null
        return Regex("""(?is)\bcontent\s*=\s*["']([^"']*)["']""").find(tag)
            ?.groupValues
            ?.get(1)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    private const val MIN_PARAGRAPH_CHARS = 40
    private const val SHORT_PARAGRAPH_CHARS = 15
    private const val PRELUDE_CHARS = 800
    private const val MIN_ARTICLE_CHARS = 140
    private const val MAX_TITLE_CHARS = 300
    private const val MAX_BYLINE_CHARS = 160

    private val DISCARDED_TAGS = listOf(
        "script", "style", "noscript", "svg", "iframe", "form", "button", "select",
        "nav", "aside", "header", "footer", "template",
    )

    private val JUNK_WORDS = listOf(
        "nav", "menu", "comment", "share", "social", "promo", "related", "recirc",
        "newsletter", "subscribe", "paywall", "cookie", "consent", "banner", "sidebar",
        "breadcrumb", "byline-links", "tags", "advert",
    ).joinToString("|") { Regex.escape(it) }
}
