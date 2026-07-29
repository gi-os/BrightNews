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

@Entity(
    tableName = "feeds",
    indices = [Index(value = ["url"], unique = true)],
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
    @Query(
        """
        SELECT a.*, f.title AS feedTitle
        FROM articles a JOIN feeds f ON f.id = a.feedId
        WHERE a.isArchived = 0
        ORDER BY a.publishedAt DESC, a.insertedAt DESC
        """,
    )
    fun observeInbox(): Flow<List<ArticleRow>>

    @Query(
        """
        SELECT a.*, f.title AS feedTitle
        FROM articles a JOIN feeds f ON f.id = a.feedId
        WHERE a.isArchived = 0 AND a.isRead = 0
        ORDER BY a.publishedAt DESC, a.insertedAt DESC
        """,
    )
    fun observeUnread(): Flow<List<ArticleRow>>

    @Query(
        """
        SELECT a.*, f.title AS feedTitle
        FROM articles a JOIN feeds f ON f.id = a.feedId
        WHERE a.isArchived = 0 AND f.isFavorite = 1
        ORDER BY a.publishedAt DESC, a.insertedAt DESC
        """,
    )
    fun observeFavoriteInbox(): Flow<List<ArticleRow>>

    @Query(
        """
        SELECT a.*, f.title AS feedTitle
        FROM articles a JOIN feeds f ON f.id = a.feedId
        WHERE a.isArchived = 0 AND a.isRead = 0 AND f.isFavorite = 1
        ORDER BY a.publishedAt DESC, a.insertedAt DESC
        """,
    )
    fun observeFavoriteUnread(): Flow<List<ArticleRow>>

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
        GROUP BY f.id
        ORDER BY f.title COLLATE NOCASE
        """,
    )
    fun observeFeeds(): Flow<List<FeedRow>>

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

    @Query("DELETE FROM articles WHERE isRead = 1 AND isStarred = 0")
    suspend fun deleteReadUnstarred()

    @Query("DELETE FROM feeds WHERE id = :feedId")
    suspend fun deleteFeed(feedId: Long)

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
    entities = [FeedEntity::class, ArticleEntity::class, AppMetadataEntity::class],
    version = 3,
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
    }
}
