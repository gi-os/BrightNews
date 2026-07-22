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
)

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
        return decodeEntities(
            value
                .replace(Regex("""(?is)<(script|style)\b.*?</\1>"""), " ")
                .replace(Regex("""(?i)<br\s*/?>"""), "\n")
                .replace(Regex("""(?i)</(p|div|li|h[1-6]|blockquote)>"""), "\n\n")
                .replace(Regex("""(?i)<li\b[^>]*>"""), "• ")
                .replace(Regex("""(?s)<[^>]+>"""), " "),
        )
            .replace('\u00A0', ' ')
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex(" *\n *"), "\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

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
            val summary = RssParser.cleanHtml(values["summary"].orEmpty().ifBlank { values["description"].orEmpty() })
            val content = RssParser.cleanHtml(values["content"].orEmpty().ifBlank { values["encoded"].orEmpty() })
                .ifBlank { summary }
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
