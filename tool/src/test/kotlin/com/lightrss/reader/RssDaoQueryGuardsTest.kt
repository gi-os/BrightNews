package com.lightrss.reader

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The exemptions that keep a settings row from emptying the archive.
 *
 * Room's queries are strings compiled into a generated DAO against a real SQLite; nothing on a
 * unit-test classpath can execute one, so what the rows actually do after "clear read articles"
 * is only assertable in an instrumented test on a device. What *is* assertable here is the shape
 * of the SQL, and that is where the bug lived: `deleteReadUnstarred` exempted starred rows and
 * nothing else, while every other delete in the same file exempted archived and unpushed-read
 * rows too. One missing clause, and a row that promises to keep your subscriptions deleted the
 * v2.6.0 archive and every offline read Gmail had not accepted yet.
 *
 * So these read the DAO source and hold the whole class of queries to the rule, rather than
 * pinning the one line that was wrong. A new bulk delete that forgets the archive fails here.
 */
class RssDaoQueryGuardsTest {

    @Test
    fun `clearing read articles keeps the archive and unpushed reads`() {
        val sql = query("deleteReadUnstarred")
        assertTrue("isStarred = 0" in sql, "saved articles are no longer exempt: $sql")
        assertTrue("isArchived = 0" in sql, "the archive is no longer exempt: $sql")
        assertTrue("pendingRead = 0" in sql, "unpushed Gmail reads are no longer exempt: $sql")
    }

    @Test
    fun `catching up does not mark archived articles read`() {
        // Not cosmetic. An archived row is in no list and in no unread count, so flipping it
        // changes nothing visible — except that a read row is what the delete above collects.
        // That is the chain by which "mark all read" then "clear read articles" lost the archive.
        assertTrue("isArchived = 0" in query("markAllRead"), "markAllRead touches archived rows")
        assertTrue("isArchived = 0" in query("markFeedRead"), "markFeedRead touches archived rows")
    }

    @Test
    fun `every bulk delete of articles exempts the archive`() {
        // Signing a mailbox out is the one deliberate exception: the issues are somebody else's
        // mail and the account that authorised them is gone, archive included.
        val allowed = setOf("deleteAllNewsletters")
        daoQueries()
            .filter { (name, sql) -> "DELETE FROM articles" in sql && name !in allowed }
            .forEach { (name, sql) ->
                assertTrue("isArchived = 0" in sql, "$name can delete an archived article: $sql")
            }
    }

    @Test
    fun `every bulk read flip exempts the archive`() {
        // Keyed updates are excluded: `markReadIn` is Gmail's own read state coming back on a
        // named list of ids, and the server is right about those whether they are archived here
        // or not.
        daoQueries()
            .filter { (_, sql) -> "UPDATE articles SET isRead = 1 " in sql && ":ids" !in sql }
            .forEach { (name, sql) ->
                assertTrue("isArchived = 0" in sql, "$name marks archived articles read: $sql")
            }
    }

    @Test
    fun `the guards are being read from a real dao, not an empty string`() {
        // A locator that silently found nothing would make every test above vacuously pass.
        val queries = daoQueries()
        assertTrue(queries.size > 20, "only ${queries.size} queries parsed out of RssDatabase.kt")
        assertTrue(queries.any { it.first == "deleteReadUnstarred" }, "deleteReadUnstarred vanished")
    }

    /* ------------------------------------------------------------------ plumbing */

    private fun query(function: String): String =
        daoQueries().firstOrNull { it.first == function }?.second
            ?: fail("no @Query on RssDao.$function")

    /** Every `@Query` in the DAO, as (function name, annotation text on one line). */
    private fun daoQueries(): List<Pair<String, String>> {
        val source = daoSource()
        val out = mutableListOf<Pair<String, String>>()
        var start = source.indexOf(QUERY_ANNOTATION)
        while (start >= 0) {
            val next = source.indexOf(QUERY_ANNOTATION, start + 1)
            val chunk = source.substring(start, if (next == -1) source.length else next)
            val name = FUNCTION.find(chunk)?.groupValues?.get(1)
            if (name != null) {
                out += name to chunk.substringBefore("fun ").replace(WHITESPACE, " ")
            }
            start = next
        }
        return out
    }

    /**
     * The DAO source, found from wherever Gradle chose to run the tests.
     *
     * Gradle runs a module's tests with the module directory as the working directory, but that
     * is a default rather than a promise, so this walks up instead of trusting one relative path.
     */
    private fun daoSource(): String {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val here: File = dir
            listOf(File(here, DAO_PATH), File(here, "tool/$DAO_PATH")).forEach { candidate ->
                if (candidate.isFile) return candidate.readText()
            }
            dir = here.parentFile
        }
        fail("RssDatabase.kt not found from ${File(".").absolutePath}")
    }

    private companion object {
        const val QUERY_ANNOTATION = "@Query"
        const val DAO_PATH = "src/main/kotlin/com/lightrss/reader/RssDatabase.kt"
        val FUNCTION = Regex("""fun\s+(\w+)\s*\(""")
        val WHITESPACE = Regex("""\s+""")
    }
}
