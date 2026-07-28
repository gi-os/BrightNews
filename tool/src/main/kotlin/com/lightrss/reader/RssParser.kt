package com.lightrss.reader

import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.StringReader
import java.net.URI
import java.security.MessageDigest
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import javax.xml.parsers.SAXParserFactory

data class ParsedFeed(
    val title: String,
    val siteUrl: String,
    val description: String,
    val items: List<ParsedArticle>,
)

data class ParsedArticle(
    val guid: String,
    val title: String,
    val link: String,
    val author: String,
    val publishedAt: Long,
    val summary: String,
    val content: String,
    val imageUrl: String = "",
    val blocks: List<ContentBlock> = emptyList(),
)

/** An ordered piece of an article body: readable text, or an image to fetch on demand. */
sealed interface ContentBlock {
    data class Text(val text: String) : ContentBlock
    data class Image(val url: String) : ContentBlock
}

/**
 * Line-oriented encoding for [ContentBlock] lists so they can live in a single Room column
 * without pulling in a serialization runtime. One block per line, `T` or `I`, tab, payload.
 */
object ContentBlocks {
    fun encode(blocks: List<ContentBlock>): String = blocks.joinToString("\n") { block ->
        when (block) {
            is ContentBlock.Text -> "T\t" + escape(block.text)
            is ContentBlock.Image -> "I\t" + escape(block.url)
        }
    }

    fun decode(value: String): List<ContentBlock> {
        if (value.isBlank()) return emptyList()
        return value.split('\n').mapNotNull { line ->
            val payload = unescape(line.substringAfter('\t', ""))
            when {
                payload.isBlank() -> null
                line.startsWith("T\t") -> ContentBlock.Text(payload)
                line.startsWith("I\t") -> ContentBlock.Image(payload)
                else -> null
            }
        }
    }

    private fun escape(value: String): String =
        value.replace("\\", "\\\\").replace("\n", "\\n").replace("\t", "\\t")

    private fun unescape(value: String): String {
        val out = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (char == '\\' && index + 1 < value.length) {
                when (value[index + 1]) {
                    'n' -> { out.append('\n'); index += 2; continue }
                    't' -> { out.append('\t'); index += 2; continue }
                    '\\' -> { out.append('\\'); index += 2; continue }
                }
            }
            out.append(char)
            index += 1
        }
        return out.toString()
    }
}

object RssParser {
    fun parse(xml: String, sourceUrl: String, now: Long = System.currentTimeMillis()): ParsedFeed {
        val handler = FeedHandler(sourceUrl, now)
        val factory = SAXParserFactory.newInstance().apply {
            isNamespaceAware = true
            isValidating = false
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }
        val reader = factory.newSAXParser().xmlReader
        reader.contentHandler = handler
        reader.entityResolver = org.xml.sax.EntityResolver { _, _ -> InputSource(StringReader("")) }
        reader.parse(InputSource(StringReader(xml)))
        return handler.result()
    }

    fun discoverFeedUrl(html: String, pageUrl: String): String? {
        val links = Regex("""<link\b[^>]*>""", RegexOption.IGNORE_CASE).findAll(html)
        for (match in links) {
            val tag = match.value
            val rel = attribute(tag, "rel")?.lowercase(Locale.US).orEmpty()
            val type = attribute(tag, "type")?.lowercase(Locale.US).orEmpty()
            if ("alternate" !in rel || ("rss" !in type && "atom" !in type && "xml" !in type)) continue
            val href = attribute(tag, "href") ?: continue
            return resolveUrl(pageUrl, decodeEntities(href))
        }
        return null
    }

    fun cleanHtml(value: String): String {
        if (value.isBlank()) return ""
        val stripped = stripMarkup(value)
        // Feeds that escaped their markup twice only reveal their tags once entities are decoded,
        // so strip again afterwards rather than printing <p> at the reader.
        val decoded = decodeEntities(stripped).let { if ('<' in it) stripMarkup(it) else it }
        return decoded
            .replace(' ', ' ')
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex(" *\n *"), "\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
            .removeLeadingNoise()
    }

    /**
     * Removes markup while leaving prose alone. A tag always opens with a letter, a slash, or a
     * markup declaration, so `5 < 10` and `if (a < b)` survive.
     */
    private fun stripMarkup(value: String): String = value
        .replace(Regex("""(?is)<(script|style)\b.*?</\1>"""), " ")
        .replace(Regex("""(?s)<!--.*?-->"""), " ")
        .replace(Regex("""(?i)<br\s*/?>"""), "\n")
        .replace(Regex("""(?i)</(p|div|li|h[1-6]|blockquote|figcaption)>"""), "\n\n")
        .replace(Regex("""(?i)<li\b[^>]*>"""), "• ")
        .replace(Regex("""(?s)</?[A-Za-z!?][^>]*>"""), " ")
        // A tag cut off by a truncated feed leaves an opening bracket with no partner.
        .replace(Regex("""(?s)<[A-Za-z/!][^<>]*$"""), " ")

    /**
     * Drops the leading ellipsis some feeds ship in place of the opening of a story, which The
     * Verge does on every item.
     */
    private fun String.removeLeadingNoise(): String =
        replace(Regex("""^(?:\s|…|\.{2,}|·|•)+"""), "")

    fun parseDate(value: String?, fallback: Long): Long {
        val raw = value?.trim().orEmpty()
        if (raw.isEmpty()) return fallback
        val attempts = listOf<(String) -> Instant>(
            { Instant.parse(it) },
            { OffsetDateTime.parse(it, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant() },
            { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() },
            {
                ZonedDateTime.parse(
                    it,
                    DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US),
                ).toInstant()
            },
            {
                ZonedDateTime.parse(
                    it,
                    DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm Z", Locale.US),
                ).toInstant()
            },
        )
        for (attempt in attempts) {
            try {
                return attempt(raw).toEpochMilli()
            } catch (_: DateTimeParseException) {
                Unit
            }
        }
        return fallback
    }

    fun stableArticleId(feedUrl: String, guid: String, link: String, title: String): String {
        val source = "$feedUrl\u0000${guid.ifBlank { link.ifBlank { title } }}"
        return MessageDigest.getInstance("SHA-256")
            .digest(source.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    /**
     * Ordered body blocks for an article: readable text interleaved with the images the feed
     * actually placed in the markup. Falls back to a single text block when there is no HTML.
     */
    fun contentBlocks(
        html: String,
        resolve: (String) -> String = { it },
        imagesRequired: Boolean = true,
    ): List<ContentBlock> {
        if (html.isBlank()) return emptyList()
        val stripped = html.replace(Regex("""(?is)<(script|style)\b.*?</\1>"""), " ")
        val blocks = mutableListOf<ContentBlock>()
        val seenImages = mutableSetOf<String>()
        var cursor = 0
        var imageCount = 0

        for (match in Regex("""(?is)<img\b[^>]*>""").findAll(stripped)) {
            if (blocks.size >= MAX_CONTENT_BLOCKS) break
            appendText(blocks, stripped.substring(cursor, match.range.first))
            cursor = match.range.last + 1
            if (imageCount >= MAX_ARTICLE_IMAGES) continue
            val url = imageSourceFromTag(match.value, resolve) ?: continue
            if (!seenImages.add(url)) continue
            blocks += ContentBlock.Image(url)
            imageCount += 1
        }
        appendText(blocks, stripped.substring(cursor.coerceAtMost(stripped.length)))

        // For feed articles, prose with no images is left to the plain content field.
        if (imagesRequired && blocks.none { it is ContentBlock.Image }) return emptyList()
        return blocks
    }

    /** Every image URL referenced by a fragment of feed HTML, in document order. */
    fun imagesInHtml(html: String, resolve: (String) -> String = { it }): List<String> =
        contentBlocks(html, resolve).filterIsInstance<ContentBlock.Image>().map { it.url }

    /**
     * Turn the payload of a scanned QR code into something [RssApi.normalizeUrl] can accept.
     * Handles bare URLs, `feed://` and `rss://` schemes, and codes that wrap a URL in text.
     */
    fun feedUrlFromScan(scanned: String): String {
        val trimmed = scanned.trim().replace(Regex("\\s+"), " ")
        if (trimmed.isEmpty()) throw IllegalArgumentException("That QR code was empty.")
        val embedded = Regex("""https?://\S+""", RegexOption.IGNORE_CASE).find(trimmed)?.value
        val candidate = when {
            embedded != null -> embedded
            trimmed.startsWith("feed://", ignoreCase = true) -> "https://" + trimmed.removeRange(0, 7)
            trimmed.startsWith("rss://", ignoreCase = true) -> "https://" + trimmed.removeRange(0, 6)
            ' ' in trimmed -> throw IllegalArgumentException("That QR code was not a feed address.")
            else -> trimmed
        }
        return candidate.trimEnd('.', ',', ')', '"', '\'', '>')
    }

    /** The best single image for an article, given feed metadata and the body HTML. */
    fun leadImage(candidates: List<String>, bodyImages: List<String>): String =
        candidates.firstOrNull { isUsableImageUrl(it) } ?: bodyImages.firstOrNull().orEmpty()

    internal fun isUsableImageUrl(url: String): Boolean {
        if (url.isBlank()) return false
        val lower = url.lowercase(Locale.US)
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false
        return TRACKING_MARKERS.none { it in lower }
    }

    internal fun looksLikeImage(url: String, type: String?, medium: String?): Boolean {
        if (type != null && type.startsWith("image/", ignoreCase = true)) return true
        if (medium != null && medium.equals("image", ignoreCase = true)) return true
        if (type != null && type.isNotBlank()) return false
        val path = url.substringBefore('?').lowercase(Locale.US)
        return IMAGE_EXTENSIONS.any { path.endsWith(it) }
    }

    private fun imageSourceFromTag(tag: String, resolve: (String) -> String): String? {
        val width = attribute(tag, "width")?.filter(Char::isDigit)?.toIntOrNull()
        val height = attribute(tag, "height")?.filter(Char::isDigit)?.toIntOrNull()
        if ((width != null && width <= 2) || (height != null && height <= 2)) return null
        val raw = attribute(tag, "src")
            ?: attribute(tag, "data-src")
            ?: attribute(tag, "data-original")
            ?: attribute(tag, "srcset")?.substringBefore(' ')
            ?: return null
        val decoded = decodeEntities(raw).trim()
        if (decoded.isBlank() || decoded.startsWith("data:", ignoreCase = true)) return null
        val resolved = resolve(decoded)
        return resolved.takeIf { isUsableImageUrl(it) }
    }

    private fun appendText(blocks: MutableList<ContentBlock>, rawHtml: String) {
        val text = cleanHtml(rawHtml)
        if (text.isBlank()) return
        val last = blocks.lastOrNull()
        if (last is ContentBlock.Text) {
            blocks[blocks.lastIndex] = ContentBlock.Text(last.text + "\n\n" + text)
        } else {
            blocks += ContentBlock.Text(text)
        }
    }

    private const val MAX_ARTICLE_IMAGES = 12
    private const val MAX_CONTENT_BLOCKS = 60

    private val IMAGE_EXTENSIONS = listOf(".jpg", ".jpeg", ".png", ".webp", ".gif", ".avif", ".heic")

    private val TRACKING_MARKERS = listOf(
        "feedburner.com/~ff",
        "feeds.feedburner.com/~r",
        "doubleclick.net",
        "/pixel",
        "pixel.",
        "1x1.",
        "spacer.gif",
        "blank.gif",
        "/track?",
        "/tracker",
        "scorecardresearch",
    )

    private fun attribute(tag: String, name: String): String? {
        val quoted = Regex("""\b${Regex.escape(name)}\s*=\s*(["'])(.*?)\1""", RegexOption.IGNORE_CASE)
            .find(tag)
            ?.groupValues
            ?.get(2)
        if (quoted != null) return quoted
        return Regex("""\b${Regex.escape(name)}\s*=\s*([^\s>]+)""", RegexOption.IGNORE_CASE)
            .find(tag)
            ?.groupValues
            ?.get(1)
    }

    private fun decodeEntities(value: String): String {
        val named = mapOf(
            "amp" to "&",
            "lt" to "<",
            "gt" to ">",
            "quot" to "\"",
            "apos" to "'",
            "nbsp" to " ",
            "ndash" to "–",
            "mdash" to "—",
            "hellip" to "…",
            "rsquo" to "’",
            "lsquo" to "‘",
            "rdquo" to "”",
            "ldquo" to "“",
        )
        return Regex("""&(#x?[0-9A-Fa-f]+|[A-Za-z]+);""").replace(value) { match ->
            val token = match.groupValues[1]
            when {
                token.startsWith("#x", ignoreCase = true) ->
                    token.drop(2).toIntOrNull(16)?.let(::codePointToString) ?: match.value
                token.startsWith('#') -> token.drop(1).toIntOrNull()?.let(::codePointToString) ?: match.value
                else -> named[token.lowercase(Locale.US)] ?: match.value
            }
        }
    }

    private fun codePointToString(codePoint: Int): String =
        runCatching { String(Character.toChars(codePoint)) }.getOrDefault("")

    private fun resolveUrl(base: String, candidate: String): String =
        runCatching { URI(base).resolve(candidate).toString() }.getOrDefault(candidate)
}

private class FeedHandler(
    private val sourceUrl: String,
    private val now: Long,
) : DefaultHandler() {
    private val path = mutableListOf<String>()
    private val textStack = mutableListOf<StringBuilder>()
    private val feedValues = mutableMapOf<String, String>()
    private val entries = mutableListOf<MutableMap<String, String>>()
    private var currentEntry: MutableMap<String, String>? = null

    override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes) {
        val name = normalizedName(localName, qName)
        path += name
        textStack += StringBuilder()
        if (name == "item" || name == "entry") currentEntry = mutableMapOf()

        if (name == "link") {
            val href = attributes.getValue("href")?.trim().orEmpty()
            if (href.isNotEmpty()) {
                val rel = attributes.getValue("rel")?.lowercase(Locale.US).orEmpty()
                val key = if (rel == "alternate" || rel.isEmpty()) "link" else "link:$rel"
                (currentEntry ?: feedValues).putIfAbsent(key, resolve(href))
            }
        }

        val entry = currentEntry
        if (entry != null) {
            when (name) {
                "enclosure" -> attributes.getValue("url")?.trim()?.takeIf { it.isNotEmpty() }?.let { url ->
                    if (RssParser.looksLikeImage(url, attributes.getValue("type"), null)) {
                        entry.putIfAbsent(IMAGE_ENCLOSURE, resolve(url))
                    }
                }
                // media:content, distinguished from a plain <content> body by carrying a url.
                "content" -> attributes.getValue("url")?.trim()?.takeIf { it.isNotEmpty() }?.let { url ->
                    if (RssParser.looksLikeImage(url, attributes.getValue("type"), attributes.getValue("medium"))) {
                        entry.putIfAbsent(IMAGE_MEDIA, resolve(url))
                    }
                }
                "thumbnail" -> attributes.getValue("url")?.trim()?.takeIf { it.isNotEmpty() }?.let { url ->
                    entry.putIfAbsent(IMAGE_THUMBNAIL, resolve(url))
                }
                // itunes:image and similar href-carrying image elements.
                "image" -> (attributes.getValue("href") ?: attributes.getValue("url"))
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { url -> entry.putIfAbsent(IMAGE_ITUNES, resolve(url)) }
                // XHTML bodies keep their markup in the XML tree rather than in CDATA.
                "img" -> attributes.getValue("src")?.trim()?.takeIf { it.isNotEmpty() }?.let { url ->
                    entry.putIfAbsent(IMAGE_XHTML, resolve(url))
                }
            }
        }
    }

    override fun characters(ch: CharArray, start: Int, length: Int) {
        textStack.lastOrNull()?.append(ch, start, length)
    }

    override fun endElement(uri: String?, localName: String?, qName: String?) {
        val name = normalizedName(localName, qName)
        val rawValue = if (textStack.isNotEmpty()) textStack.removeAt(textStack.lastIndex).toString() else ""
        val value = rawValue.trim()
        val entry = currentEntry

        if (entry != null && name != "item" && name != "entry") {
            if (value.isNotEmpty()) {
                when (name) {
                    "title", "guid", "id", "link", "author", "creator", "name", "pubdate",
                    "published", "updated", "description", "summary", "content", "encoded" ->
                        entry.putIfAbsent(entryKey(name), value)
                }
            }
        } else if (entry == null && value.isNotEmpty()) {
            when (name) {
                "title", "link", "subtitle", "description" -> feedValues.putIfAbsent(name, value)
            }
        }

        if (name == "item" || name == "entry") {
            currentEntry?.let(entries::add)
            currentEntry = null
        }
        if (path.isNotEmpty()) path.removeAt(path.lastIndex)
        if (rawValue.isNotBlank()) textStack.lastOrNull()?.append(' ')?.append(rawValue)
    }

    fun result(): ParsedFeed {
        val articles = entries.mapIndexedNotNull { index, values ->
            val title = RssParser.cleanHtml(values["title"].orEmpty()).ifBlank { "Untitled" }
            val rawLink = values["link"].orEmpty()
            val link = if (rawLink.isBlank()) "" else resolve(rawLink)
            val guid = values["guid"].orEmpty().ifBlank { values["id"].orEmpty() }.ifBlank { link }
            val rawSummary = values["summary"].orEmpty().ifBlank { values["description"].orEmpty() }
            val rawBody = values["encoded"].orEmpty()
                .ifBlank { values["content"].orEmpty() }
                .ifBlank { rawSummary }
            val summary = RssParser.cleanHtml(rawSummary)
            val content = RssParser.cleanHtml(rawBody).ifBlank { summary }
            val blocks = RssParser.contentBlocks(rawBody, ::resolve)
            val bodyImages = blocks.filterIsInstance<ContentBlock.Image>().map { it.url }
            val imageUrl = RssParser.leadImage(
                candidates = listOfNotNull(
                    values[IMAGE_MEDIA],
                    values[IMAGE_ENCLOSURE],
                    values[IMAGE_THUMBNAIL],
                    values[IMAGE_ITUNES],
                    values[IMAGE_XHTML],
                ),
                bodyImages = bodyImages,
            )
            val dateText = values["published"].orEmpty()
                .ifBlank { values["pubdate"].orEmpty() }
                .ifBlank { values["updated"].orEmpty() }
            val fallback = now - index * 1_000L
            ParsedArticle(
                guid = guid.ifBlank { "$title-$dateText" },
                title = title,
                link = link,
                author = RssParser.cleanHtml(
                    values["creator"].orEmpty()
                        .ifBlank { values["author"].orEmpty() }
                        .ifBlank { values["name"].orEmpty() },
                ),
                publishedAt = RssParser.parseDate(dateText, fallback),
                summary = summary,
                content = content,
                imageUrl = imageUrl,
                blocks = blocks,
            )
        }.filter { it.guid.isNotBlank() || it.title.isNotBlank() }

        if (articles.isEmpty()) throw IllegalArgumentException("This address did not contain any RSS or Atom articles.")
        return ParsedFeed(
            title = RssParser.cleanHtml(feedValues["title"].orEmpty()).ifBlank { URI(sourceUrl).host ?: "RSS Feed" },
            siteUrl = feedValues["link"].orEmpty().let { if (it.isBlank()) "" else resolve(it) },
            description = RssParser.cleanHtml(
                feedValues["description"].orEmpty().ifBlank { feedValues["subtitle"].orEmpty() },
            ),
            items = articles,
        )
    }

    private fun entryKey(name: String): String = when {
        name == "description" -> "description"
        name == "summary" -> "summary"
        name == "encoded" -> "encoded"
        name == "content" -> "content"
        else -> name
    }

    private fun normalizedName(localName: String?, qName: String?): String =
        localName?.takeIf { it.isNotBlank() }?.lowercase(Locale.US)
            ?: qName.orEmpty().substringAfter(':').lowercase(Locale.US)

    private fun resolve(value: String): String =
        runCatching { URI(sourceUrl).resolve(value).toString() }.getOrDefault(value)
}

private const val IMAGE_ENCLOSURE = "image:enclosure"
private const val IMAGE_MEDIA = "image:media"
private const val IMAGE_THUMBNAIL = "image:thumbnail"
private const val IMAGE_ITUNES = "image:itunes"
private const val IMAGE_XHTML = "image:xhtml"
