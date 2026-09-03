package com.lightrss.reader

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.security.MessageDigest
import java.util.Locale

/**
 * Kagi News, as a source.
 *
 * Kagi publishes its whole edition as public JSON with no key and no account: an index
 * (`kite.json`) naming ~190 categories, and one file per category holding about a dozen
 * "clusters" — stories synthesised from forty-odd articles each, with a summary, highlights,
 * perspectives, a quote, a timeline and the source list. It refreshes about once a day. The
 * format is Kagi's open Kite project; reuse of the JSON is explicitly allowed.
 *
 * Nothing here knows about the database. A category becomes a [FeedEntity] whose url is the
 * category file; a cluster becomes an [ArticleEntity] whose sections travel as [ContentBlock]s,
 * so the reader, lists, search, saved and archive all work on it without knowing where it came
 * from — the same merge that made a Gmail label a feed.
 */
object Kagi {

    const val BASE_URL = "https://news.kagi.com/"
    const val INDEX_URL = BASE_URL + "kite.json"

    /** One entry of `kite.json`. [file] is the bare filename; [url] is where to fetch it. */
    data class Category(val name: String, val file: String) {
        val url: String get() = categoryUrl(file)
    }

    /** How the picker shelves a category. */
    enum class Group { CORE, PLACES, TOPICS }

    /**
     * A category on the picker: its shelf, and the parent row it folds under, if any.
     * `"USA | New York City"` folds under `USA`; a bare country folds under `Countries`.
     */
    data class Shelved(val category: Category, val group: Group, val parent: String?) {
        /** The name shown once the parent has already said the first half. */
        val shortName: String
            get() = if (parent != null && " | " in category.name) category.name.substringAfter(" | ") else category.name
    }

    /** A source article as Kagi lists it under a story. */
    data class SourceArticle(val title: String, val link: String, val domain: String)

    /** One synthesised story, with only the sections the reader shows. */
    data class Cluster(
        val rank: Int,
        val title: String,
        val topic: String,
        val summary: String,
        val location: String,
        val highlights: List<String>,
        val perspectives: List<String>,
        val quote: String,
        val quoteAuthor: String,
        val didYouKnow: String,
        val timeline: List<String>,
        val imageUrl: String,
        val sources: List<SourceArticle>,
        val sourceCount: Int,
    )

    /** A category file, parsed. [publishedAt] is the edition's timestamp in epoch millis. */
    data class Edition(val category: String, val publishedAt: Long, val clusters: List<Cluster>)

    /**
     * Category filenames carry `|`, spaces, commas, parentheses and the odd umlaut
     * (`germany_|_berlin.json`, `usa_|_austin,_tx.json`). They are one path segment; encode
     * exactly that and nothing else.
     */
    fun categoryUrl(file: String): String {
        val encoded = buildString {
            for (byte in file.toByteArray(Charsets.UTF_8)) {
                val c = byte.toInt() and 0xff
                val ch = c.toChar()
                if ((ch.isLetterOrDigit() && c < 128) || ch in "-_.~") append(ch) else append("%%%02X".format(c))
            }
        }
        return BASE_URL + encoded
    }

    fun parseIndex(json: String): List<Category> {
        val root = Json.parseToJsonElement(json).jsonObject
        return root["categories"]?.jsonArray.orEmpty().mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val name = obj.string("name")
            val file = obj.string("file")
            if (name.isBlank() || file.isBlank()) null else Category(name, file)
        }
    }

    /**
     * Shelves the index for the picker. Kagi's index is already ordered: the general categories
     * first, then everything else alphabetically, so "the first few" is a stable test.
     *
     * A category goes to PLACES when its name carries a `|` (region under a parent) or is a
     * country or place in the list below; everything else is a topic. The place list is a
     * closed set on purpose — guessing from a proper noun would shelve "Apple" and "Google"
     * as places.
     */
    fun shelve(categories: List<Category>): List<Shelved> {
        val core = categories.take(CORE_COUNT).map { it.name }.toSet()
        return categories.map { category ->
            val name = category.name
            val prefix = name.substringBefore(" | ")
            when {
                name in core || name == ON_THIS_DAY -> Shelved(category, Group.CORE, null)
                // "USA | Texas" is a place under USA; "Healthcare | USA" is a topic.
                " | " in name && (prefix in PLACES || prefix in core) -> Shelved(category, Group.PLACES, prefix)
                " | " in name -> Shelved(category, Group.TOPICS, null)
                name.substringBefore(" (") in PLACES -> Shelved(category, Group.PLACES, COUNTRIES_PARENT)
                else -> Shelved(category, Group.TOPICS, null)
            }
        }
    }

    fun parseEdition(json: String): Edition {
        val root = Json.parseToJsonElement(json).jsonObject
        val category = root.string("category")
        val timestamp = root["timestamp"]?.long() ?: (System.currentTimeMillis() / 1000)
        val clusters = root["clusters"]?.jsonArray.orEmpty().mapIndexedNotNull { index, element ->
            val obj = element as? JsonObject ?: return@mapIndexedNotNull null
            val title = obj.string("title")
            if (title.isBlank()) return@mapIndexedNotNull null
            Cluster(
                rank = obj["cluster_number"]?.long()?.toInt() ?: (index + 1),
                title = title,
                topic = obj.string("category"),
                summary = obj.string("short_summary"),
                location = obj.string("location"),
                highlights = obj.strings("talking_points"),
                perspectives = obj["perspectives"]?.asArray().orEmpty().mapNotNull { perspective ->
                    (perspective as? JsonObject)?.string("text")?.takeIf { it.isNotBlank() }
                },
                quote = obj.string("quote"),
                quoteAuthor = listOf(obj.string("quote_author"), obj.string("quote_attribution"))
                    .filter { it.isNotBlank() }
                    .joinToString(", "),
                didYouKnow = obj.string("did_you_know"),
                timeline = obj["timeline"]?.asArray().orEmpty().mapNotNull { entry ->
                    val item = entry as? JsonObject ?: return@mapNotNull null
                    val date = item.string("date")
                    val content = item.string("content")
                    if (content.isBlank()) {
                        null
                    } else {
                        listOf(date, stripCitations(content)).filter { it.isNotBlank() }.joinToString(" — ")
                    }
                },
                imageUrl = (obj["primary_image"] as? JsonObject)?.string("url").orEmpty(),
                sources = obj["articles"]?.asArray().orEmpty().mapNotNull { entry ->
                    val item = entry as? JsonObject ?: return@mapNotNull null
                    val link = item.string("link")
                    if (!link.startsWith("http", ignoreCase = true)) return@mapNotNull null
                    SourceArticle(
                        title = item.string("title").ifBlank { link },
                        link = link,
                        domain = item.string("domain").ifBlank { hostOf(link) },
                    )
                },
                sourceCount = obj["number_of_titles"]?.long()?.toInt() ?: 0,
            )
        }
        return Edition(category, timestamp * 1000, clusters)
    }

    /**
     * A story as the reader shows it. Sections only when Kagi filled them; the source list is
     * capped because forty near-identical wire headlines are not reading, they are proof.
     */
    fun blocks(cluster: Cluster): List<ContentBlock> = buildList {
        if (cluster.imageUrl.isNotBlank()) add(ContentBlock.Image(cluster.imageUrl))
        if (cluster.summary.isNotBlank()) add(ContentBlock.Text(stripCitations(cluster.summary)))
        section("Highlights", cluster.highlights.map { stripCitations(it) })
        if (cluster.quote.isNotBlank()) {
            add(ContentBlock.Heading("Quote"))
            val attribution = if (cluster.quoteAuthor.isNotBlank()) "\n— ${cluster.quoteAuthor}" else ""
            add(ContentBlock.Text("“${cluster.quote.trim()}”$attribution"))
        }
        section("Perspectives", cluster.perspectives.map { stripCitations(it) })
        section("Timeline", cluster.timeline)
        if (cluster.didYouKnow.isNotBlank()) {
            add(ContentBlock.Heading("Did you know"))
            add(ContentBlock.Text(stripCitations(cluster.didYouKnow)))
        }
        val sources = cluster.sources.distinctBy { it.domain }.take(MAX_SOURCES)
        if (sources.isNotEmpty()) {
            val total = maxOf(cluster.sourceCount, cluster.sources.size)
            add(ContentBlock.Heading(if (total > sources.size) "Sources ($total)" else "Sources"))
            sources.forEach { add(ContentBlock.Link(it.title, it.link)) }
        }
    }

    private fun MutableList<ContentBlock>.section(heading: String, items: List<String>) {
        val kept = items.filter { it.isNotBlank() }
        if (kept.isEmpty()) return
        add(ContentBlock.Heading(heading))
        kept.forEach { add(ContentBlock.Text(it)) }
    }

    /**
     * Kagi footnotes its prose with `[domain.com#3]` markers pointing into the source list. The
     * list is shown in full below, so the markers are noise in the paragraph.
     */
    fun stripCitations(text: String): String =
        text.replace(CITATION, "")
            .replace(Regex("\\s{2,}"), " ")
            .replace(Regex(" ([,.;:!?])"), "\$1")
            .trim()

    /**
     * The story's identity within a day. Kagi has no story id, and a story keeps its title
     * across the categories it lands in, so a normalised title is what lets the same story be
     * recognised in World and in USA — and lets a republish within the day not read as new.
     */
    fun storyGuid(title: String): String {
        val normalised = title.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), " ").trim()
        val digest = MessageDigest.getInstance("SHA-256").digest(normalised.toByteArray(Charsets.UTF_8))
        return "kagi:" + digest.take(12).joinToString("") { "%02x".format(it) }
    }

    /**
     * The row's date, which also fixes the order: every story in an edition shares the
     * edition's timestamp, and the lists sort newest first, so the edition time is stepped
     * back one second per rank and Kagi's own ranking falls out of the ordinary sort.
     */
    fun storyPublishedAt(edition: Edition, cluster: Cluster): Long =
        edition.publishedAt - cluster.rank * 1000L

    private fun hostOf(url: String): String =
        runCatching { java.net.URI(url).host.orEmpty().removePrefix("www.").lowercase(Locale.US) }.getOrDefault("")

    private fun JsonObject.string(key: String): String = when (val value = this[key]) {
        null, JsonNull -> ""
        is JsonPrimitive -> value.content.trim()
        else -> ""
    }

    private fun JsonObject.strings(key: String): List<String> =
        this[key]?.asArray().orEmpty().mapNotNull { element ->
            (element as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotBlank() }
        }

    private fun JsonElement.asArray(): JsonArray? = this as? JsonArray

    private fun JsonElement.long(): Long? = (this as? JsonPrimitive)?.longOrNull

    private val CITATION = Regex("""\s*\[[^\[\]]*?#\d+(?:,\s*[^\[\]]*?#\d+)*\]""")

    private const val CORE_COUNT = 8
    private const val ON_THIS_DAY = "OnThisDay"
    const val COUNTRIES_PARENT = "Countries"
    private const val MAX_SOURCES = 12

    /** Every place in Kagi's index that is not already a `Parent | Child` pair. */
    private val PLACES = setOf(
        "Africa", "Albania", "Algeria", "Argentina", "Asia", "Australia", "Austria", "Bangladesh",
        "Belgium", "Bosnia and Herzegovina", "Brazil", "Bulgaria", "Canada", "Caribbean", "Chile",
        "China", "Colombia", "Costa Rica", "Croatia", "Czech Republic", "Denmark",
        "Dominican Republic", "DR Congo", "Estonia", "Ethiopia", "Europe", "Finland", "France",
        "Georgia", "Germany", "Ghana", "Greece", "Hong Kong", "Hungary", "Iceland", "India",
        "Indonesia", "Iran", "Ireland", "Israel", "Italy", "Japan", "Kenya", "Kosovo", "Latvia",
        "Lithuania", "Macau", "Madeira Island", "Malaysia", "Malta", "Mexico", "Middle East",
        "Moldova", "Montenegro", "Morocco", "New Zealand", "Nigeria", "North Carolina",
        "North Macedonia", "Norway", "Pakistan", "Palestine", "Philippines", "Poland", "Portugal",
        "Romania", "Russia", "Rwanda", "Serbia", "Singapore", "Slovakia", "Slovenia",
        "South Africa", "South Korea", "Spain", "Sweden", "Switzerland", "Taiwan", "Tanzania",
        "Thailand", "The Netherlands", "Turkey", "UK", "Ukraine", "Venezuela", "Vietnam",
        "Bay Area",
    )
}
