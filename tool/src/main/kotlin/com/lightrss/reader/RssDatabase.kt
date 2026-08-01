package com.lightrss.reader

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/** The two things a source of articles can be. Stored as text so a third is additive. */
object Source {
    const val RSS = "RSS"
    const val GMAIL = "GMAIL"
}

@Entity(
    tableName = "feeds",
    indices = [Index(value = ["url"], unique = true), Index("sourceType")],
)
data class FeedEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val url: String,
    val siteUrl: String = "",
    val description: String = "",
    val addedAt: Long = System.currentTimeMillis(),
    val lastFetchedAt: Long = 0,
    val etag: String? = null,
    val lastModified: String? = null,
    val errorMessage: String? = null,
    /** Whether this feed is one of the favourites the home screen can be narrowed to. */
    val isFavorite: Boolean = false,
    /**
     * [Source.RSS] or [Source.GMAIL].
     *
     * A Gmail label is a feed. That is the whole merge: subscribe to a label and its messages
     * become articles like any other, which is what lets the reader, search, saved items,
     * the image cache and read state all work on newsletters without knowing they exist.
     * Only [com.lightrss.reader.RssRepository.refreshFeedInternal] branches on this.
     */
    val sourceType: String = Source.RSS,
    /** The Gmail label's human name, for [Source.GMAIL] feeds. Null for RSS. */
    val gmailLabel: String? = null,
    /** The label's opaque Gmail id, cached so every sync doesn't re-resolve the name. */
    val gmailLabelId: String? = null,
)

@Entity(
    tableName = "articles",
    foreignKeys = [
        ForeignKey(
            entity = FeedEntity::class,
            parentColumns = ["id"],
            childColumns = ["feedId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("feedId"), Index("publishedAt"), Index("isRead"), Index("isStarred")],
)
data class ArticleEntity(
    @PrimaryKey val id: String,
    val feedId: Long,
    val guid: String,
    val title: String,
    val link: String,
    val author: String = "",
    val publishedAt: Long,
    val summary: String = "",
    val content: String = "",
    /** Lead image for the article, or "" when the feed offered none. */
    val imageUrl: String = "",
    /** Body blocks encoded by [ContentBlocks]; empty when the article has no inline images. */
    val contentBlocks: String = "",
    val isRead: Boolean = false,
    val isStarred: Boolean = false,
    val isArchived: Boolean = false,
    val insertedAt: Long = System.currentTimeMillis(),
    /**
     * Read on the phone, not yet accepted by Gmail. Only ever set on [Source.GMAIL] articles.
     *
     * A read has to land locally the instant it happens — the phone is often offline and a
     * reader that waits for the network to agree feels broken. This flag is what makes that
     * honest rather than a lie: the read is pushed on the next sync, and until it settles the
     * row is excluded from both directions of read-state reconciliation so the server's stale
     * answer cannot erase it.
     */
    val pendingRead: Boolean = false,
)

/**
 * A newsletter's rewritten HTML, kept out of `articles` on purpose.
 *
 * An issue of a design newsletter with its art inlined runs to a few hundred kilobytes, and
 * every list query in this app is `SELECT a.*` — putting the body in that table would drag the
 * home screen down in proportion to how much has been read. LightNews kept these as files
 * under filesDir, which cost it a staging-write dance, an orphan sweep and a `hasBody` check
 * on every sync. A child table with `ON DELETE CASCADE` is the same isolation with none of
 * that: the body cannot outlive its article, and it cannot go missing while the row survives.
 */
@Entity(
    tableName = "newsletter_bodies",
    foreignKeys = [
        ForeignKey(
            entity = ArticleEntity::class,
            parentColumns = ["id"],
            childColumns = ["articleId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class NewsletterBodyEntity(
    @PrimaryKey val articleId: String,
    /** The message's HTML, cid: images already inlined. Empty when it was text-only. */
    val html: String,
)

@Entity(tableName = "app_metadata")
data class AppMetadataEntity(
    @PrimaryKey val key: String,
    val value: String,
)

data class ArticleRow(
    @Embedded val article: ArticleEntity,
    val feedTitle: String,
)

data class FeedRow(
    @Embedded val feed: FeedEntity,
    val unreadCount: Int,
    val articleCount: Int,
)

@Dao
interface RssDao {
    /**
     * The article list, for every screen that shows one.
     *
     * One query rather than the four it replaces. Adding a section filter to the previous
     * shape would have meant eight near-identical statements, and every one of them a place
     * for the ordering or the isArchived clause to drift. A null [source] means both sections.
     */
    @Query(
        """
        SELECT a.*, f.title AS feedTitle
        FROM articles a JOIN feeds f ON f.id = a.feedId
        WHERE a.isArchived = 0
          AND (:unreadOnly = 0 OR a.isRead = 0)
          AND (:favoritesOnly = 0 OR f.isFavorite = 1)
          AND (:source IS NULL OR f.sourceType = :source)
        ORDER BY a.publishedAt DESC, a.insertedAt DESC
        """,
    )
    fun observeInbox(
        unreadOnly: Boolean,
        favoritesOnly: Boolean,
        source: String?,
    ): Flow<List<ArticleRow>>

    @Query(
        """
        SELECT COUNT(*) FROM articles a JOIN feeds f ON f.id = a.feedId
        WHERE a.isArchived = 0 AND a.isRead = 0 AND f.sourceType = :source
        """,
    )
    fun observeUnreadCount(source: String): Flow<Int>

    @Query(
        """
        SELECT a.*, f.title AS feedTitle
        FROM articles a JOIN feeds f ON f.id = a.feedId
        WHERE a.isStarred = 1
        ORDER BY a.publishedAt DESC, a.insertedAt DESC
        """,
    )
    fun observeStarred(): Flow<List<ArticleRow>>

    @Query(
        """
        SELECT a.*, f.title AS feedTitle
        FROM articles a JOIN feeds f ON f.id = a.feedId
        WHERE a.feedId = :feedId AND a.isArchived = 0
        ORDER BY a.publishedAt DESC, a.insertedAt DESC
        """,
    )
    fun observeFeedArticles(feedId: Long): Flow<List<ArticleRow>>

    @Query(
        """
        SELECT a.*, f.title AS feedTitle
        FROM articles a JOIN feeds f ON f.id = a.feedId
        WHERE a.isArchived = 0 AND (
            a.title LIKE '%' || :query || '%' OR
            a.summary LIKE '%' || :query || '%' OR
            a.content LIKE '%' || :query || '%' OR
            a.author LIKE '%' || :query || '%' OR
            f.title LIKE '%' || :query || '%'
        )
        ORDER BY a.publishedAt DESC, a.insertedAt DESC
        LIMIT 200
        """,
    )
    fun observeSearch(query: String): Flow<List<ArticleRow>>

    @Query(
        """
        SELECT f.*,
            CAST(SUM(CASE WHEN a.isRead = 0 AND a.isArchived = 0 THEN 1 ELSE 0 END) AS INTEGER) AS unreadCount,
            CAST(COUNT(a.id) AS INTEGER) AS articleCount
        FROM feeds f LEFT JOIN articles a ON a.feedId = f.id
        WHERE :source IS NULL OR f.sourceType = :source
        GROUP BY f.id
        ORDER BY f.title COLLATE NOCASE
        """,
    )
    fun observeFeeds(source: String?): Flow<List<FeedRow>>

    @Query("SELECT COUNT(*) FROM feeds WHERE isFavorite = 1")
    fun observeFavoriteFeedCount(): Flow<Int>

    @Query("SELECT * FROM feeds ORDER BY title COLLATE NOCASE")
    suspend fun getFeeds(): List<FeedEntity>


    @Query("SELECT * FROM feeds WHERE id = :feedId LIMIT 1")
    suspend fun getFeed(feedId: Long): FeedEntity?

    @Query("SELECT * FROM feeds WHERE url = :url LIMIT 1")
    suspend fun getFeedByUrl(url: String): FeedEntity?

    @Query("SELECT * FROM feeds WHERE id = :feedId LIMIT 1")
    fun observeFeed(feedId: Long): Flow<FeedEntity?>

    @Query(
        """
        SELECT a.*, f.title AS feedTitle
        FROM articles a JOIN feeds f ON f.id = a.feedId
        WHERE a.id = :articleId LIMIT 1
        """,
    )
    fun observeArticle(articleId: String): Flow<ArticleRow?>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertFeed(feed: FeedEntity): Long

    @Transaction
    suspend fun insertFeedWithArticles(
        feed: FeedEntity,
        articles: List<ArticleEntity>,
    ): Long {
        val feedId = insertFeed(feed)
        storeArticles(articles.map { it.copy(feedId = feedId) })
        return feedId
    }

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertArticles(articles: List<ArticleEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putMetadata(metadata: AppMetadataEntity)

    @Query("SELECT value FROM app_metadata WHERE `key` = :key LIMIT 1")
    suspend fun getMetadata(key: String): String?

    @Query("SELECT value FROM app_metadata WHERE `key` = :key LIMIT 1")
    fun observeMetadata(key: String): Flow<String?>

    /**
     * Really remove the key, rather than storing an empty string in its place.
     *
     * Signing out has to leave no row behind: the OAuth code checks null to mean "never set",
     * and a blank value that reads back as present is how a cleared credential comes back to
     * life as an empty one.
     */
    @Query("DELETE FROM app_metadata WHERE `key` = :key")
    suspend fun deleteMetadata(key: String)

    @Query(
        """
        UPDATE feeds SET
            title = :title,
            url = :url,
            siteUrl = :siteUrl,
            description = :description,
            lastFetchedAt = :fetchedAt,
            etag = :etag,
            lastModified = :lastModified,
            errorMessage = NULL
        WHERE id = :feedId
        """,
    )
    suspend fun updateFeedAfterRefresh(
        feedId: Long,
        title: String,
        url: String,
        siteUrl: String,
        description: String,
        fetchedAt: Long,
        etag: String?,
        lastModified: String?,
    )

    @Query(
        "UPDATE feeds SET lastFetchedAt = :fetchedAt, errorMessage = NULL WHERE id = :feedId",
    )
    suspend fun markFeedNotModified(feedId: Long, fetchedAt: Long)

    @Query("UPDATE feeds SET errorMessage = :message WHERE id = :feedId")
    suspend fun setFeedError(feedId: Long, message: String)

    @Query("UPDATE feeds SET isFavorite = :isFavorite WHERE id = :feedId")
    suspend fun setFeedFavorite(feedId: Long, isFavorite: Boolean)

    @Query(
        """
        UPDATE articles SET
            title = :title,
            link = :link,
            author = :author,
            publishedAt = :publishedAt,
            summary = :summary,
            content = :content,
            imageUrl = :imageUrl,
            contentBlocks = :contentBlocks
        WHERE id = :id
        """,
    )
    suspend fun updateArticleContent(
        id: String,
        title: String,
        link: String,
        author: String,
        publishedAt: Long,
        summary: String,
        content: String,
        imageUrl: String,
        contentBlocks: String,
    )

    @Query("UPDATE articles SET isRead = :isRead WHERE id = :articleId")
    suspend fun setRead(articleId: String, isRead: Boolean)

    @Query("UPDATE articles SET isStarred = :isStarred WHERE id = :articleId")
    suspend fun setStarred(articleId: String, isStarred: Boolean)

    @Query("UPDATE articles SET isArchived = :isArchived WHERE id = :articleId")
    suspend fun setArchived(articleId: String, isArchived: Boolean)

    @Query("UPDATE articles SET isRead = 1 WHERE feedId = :feedId")
    suspend fun markFeedRead(feedId: Long)

    @Query("UPDATE articles SET isRead = 1")
    suspend fun markAllRead()

    /**
     * Queue a bulk read for Gmail.
     *
     * "Mark all read" has to reach the mailbox too, or the next sync's reconciliation reads
     * the server's unread list and puts every one of them back. Flagging rather than pushing
     * inline is deliberate: catching up on a hundred issues should not block on the network,
     * and [pendingReadIds] will pick them up on the next refresh whether it is a minute or a
     * week away. A null [feedId] means every subscribed label.
     */
    @Query(
        """
        UPDATE articles SET pendingRead = 1
        WHERE isRead = 1 AND pendingRead = 0
          AND feedId IN (
            SELECT id FROM feeds
            WHERE sourceType = 'GMAIL' AND (:feedId IS NULL OR id = :feedId)
          )
        """,
    )
    suspend fun queueNewsletterReads(feedId: Long?)

    @Query("DELETE FROM articles WHERE isRead = 1 AND isStarred = 0")
    suspend fun deleteReadUnstarred()

    @Query("DELETE FROM feeds WHERE id = :feedId")
    suspend fun deleteFeed(feedId: Long)

    /* ------------------------------------------------------------------ newsletters */

    @Query("UPDATE feeds SET gmailLabelId = :labelId WHERE id = :feedId")
    suspend fun setGmailLabelId(feedId: Long, labelId: String?)

    @Query("SELECT * FROM articles WHERE id = :id LIMIT 1")
    suspend fun getArticle(id: String): ArticleEntity?

    @Query("SELECT id FROM articles WHERE feedId = :feedId")
    suspend fun articleIdsIn(feedId: Long): List<String>

    /**
     * Ids in this feed that have a body row.
     *
     * The sync refetches anything present in the label but absent here, so a row whose body
     * never arrived — a fetch that failed after the article was written — is repaired rather
     * than left showing a one-line snippet forever.
     */
    @Query(
        """
        SELECT a.id FROM articles a JOIN newsletter_bodies b ON b.articleId = a.id
        WHERE a.feedId = :feedId
        """,
    )
    suspend fun articleIdsWithBody(feedId: Long): List<String>

    @Query("SELECT id FROM articles WHERE feedId = :feedId AND pendingRead = 1")
    suspend fun pendingReadIds(feedId: Long): List<String>

    @Query("UPDATE articles SET isRead = :isRead, pendingRead = :pending WHERE id = :id")
    suspend fun setReadPending(id: String, isRead: Boolean, pending: Boolean)

    @Query("UPDATE articles SET pendingRead = 0 WHERE id IN (:ids)")
    suspend fun settleReads(ids: List<String>)

    /**
     * Read-state reconciliation, applied only to ids the caller actually observed.
     *
     * pendingRead rows are excluded from both directions: those carry a read Gmail has not
     * accepted yet, and letting the server's stale answer win would erase it.
     */
    @Query("UPDATE articles SET isRead = 1 WHERE pendingRead = 0 AND id IN (:ids)")
    suspend fun markReadIn(ids: List<String>)

    @Query("UPDATE articles SET isRead = 0 WHERE pendingRead = 0 AND id IN (:ids)")
    suspend fun markUnreadIn(ids: List<String>)

    /**
     * Drop newsletters that are no longer in the label — but never one the reader saved, or
     * one still holding a read Gmail has not accepted. Bodies go with them, by cascade.
     */
    @Query("DELETE FROM articles WHERE id IN (:ids) AND isStarred = 0 AND pendingRead = 0")
    suspend fun deleteStaleNewsletters(ids: List<String>)

    @Query("SELECT html FROM newsletter_bodies WHERE articleId = :articleId LIMIT 1")
    suspend fun getBody(articleId: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putBody(body: NewsletterBodyEntity)

    /**
     * Throw every cached body away, so the next sync refetches.
     *
     * Switching images on has to do this. Inline art is resolved once, when a message is
     * stored, because a WebView cannot attach an OAuth header to fetch a MIME part later — so
     * a body cached with images off has no art in it and never will.
     */
    @Query("DELETE FROM newsletter_bodies")
    suspend fun clearBodies()

    /**
     * One newsletter, body and row together.
     *
     * A transaction because the body's foreign key requires the article to exist first, and
     * because a process killed between the two would otherwise leave a row claiming HTML that
     * is not there. The read state of an existing row survives a refetch: [pendingRead]
     * carries a read the user has already made, and resurrecting it as unread would lose it.
     */
    @Transaction
    suspend fun storeNewsletter(article: ArticleEntity, html: String) {
        val previous = getArticle(article.id)
        val merged = when {
            previous == null -> article
            previous.pendingRead -> article.copy(
                isRead = true,
                pendingRead = true,
                isStarred = previous.isStarred,
                isArchived = previous.isArchived,
            )
            else -> article.copy(
                isStarred = previous.isStarred,
                isArchived = previous.isArchived,
            )
        }
        insertArticles(listOf(merged))
        updateArticleContent(
            id = merged.id,
            title = merged.title,
            link = merged.link,
            author = merged.author,
            publishedAt = merged.publishedAt,
            summary = merged.summary,
            content = merged.content,
            imageUrl = merged.imageUrl,
            contentBlocks = merged.contentBlocks,
        )
        setReadPending(merged.id, merged.isRead, merged.pendingRead)
        putBody(NewsletterBodyEntity(merged.id, html))
    }

    /**
     * Keep the newest [keep] issues of one label; a year of dailies would fill the phone.
     * Saved items and unpushed reads are exempt — dropping either loses something silently.
     * Bodies go with them, by cascade.
     */
    @Query(
        """
        DELETE FROM articles
        WHERE feedId = :feedId AND isStarred = 0 AND pendingRead = 0 AND id NOT IN (
            SELECT id FROM articles WHERE feedId = :feedId
            ORDER BY publishedAt DESC, insertedAt DESC LIMIT :keep
        )
        """,
    )
    suspend fun trimNewsletters(feedId: Long, keep: Int)

    @Transaction
    suspend fun storeArticles(articles: List<ArticleEntity>) {
        insertArticles(articles)
        articles.forEach { article ->
            updateArticleContent(
                id = article.id,
                title = article.title,
                link = article.link,
                author = article.author,
                publishedAt = article.publishedAt,
                summary = article.summary,
                content = article.content,
                imageUrl = article.imageUrl,
                contentBlocks = article.contentBlocks,
            )
        }
    }
}

@Database(
    entities = [
        FeedEntity::class,
        ArticleEntity::class,
        NewsletterBodyEntity::class,
        AppMetadataEntity::class,
    ],
    version = 4,
    exportSchema = false,
)
abstract class RssDatabase : RoomDatabase() {
    abstract fun rssDao(): RssDao

    companion object {
        /** Adds article image columns. Existing rows keep their text and pick images up on refresh. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE articles ADD COLUMN imageUrl TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE articles ADD COLUMN contentBlocks TEXT NOT NULL DEFAULT ''")
            }
        }

        /** Adds the feed favourite flag. Nothing is a favourite until the reader stars it. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE feeds ADD COLUMN isFavorite INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * Newsletters arrive.
         *
         * Everything already in the database is RSS, which is exactly what the column
         * defaults say, so no existing row is touched and no feed or article is lost on the
         * upgrade from LightRSS to LightNews. The index on sourceType is what keeps the
         * section lists from scanning the whole articles table once a mailbox is attached.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE feeds ADD COLUMN sourceType TEXT NOT NULL DEFAULT 'RSS'")
                db.execSQL("ALTER TABLE feeds ADD COLUMN gmailLabel TEXT")
                db.execSQL("ALTER TABLE feeds ADD COLUMN gmailLabelId TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_feeds_sourceType ON feeds(sourceType)")
                db.execSQL("ALTER TABLE articles ADD COLUMN pendingRead INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS newsletter_bodies (
                        articleId TEXT NOT NULL PRIMARY KEY,
                        html TEXT NOT NULL,
                        FOREIGN KEY(articleId) REFERENCES articles(id) ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
            }
        }
    }
}
