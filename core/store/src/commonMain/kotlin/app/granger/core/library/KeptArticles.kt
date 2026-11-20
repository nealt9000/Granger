package app.granger.core.library

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * An online article kept on this phone on purpose (online/offline spec §5, Plan O2 Task 3): its page and pictures sit in
 * the app's files, outside any pack, and it's served by the online source it came from, so its tab and history don't
 * change. [withPictures] false is a text-only keep, a shorter copy: online wins over it when the internet is there.
 */
@Entity(tableName = "kept_articles", primaryKeys = ["lang", "title"])
data class KeptArticle(
    val lang: String,
    /** The article's key, as its online source's path (`Brú_na_Bóinne`). */
    val title: String,
    val qid: Long?,
    /** Where its files are, relative to the kept folder. */
    val path: String,
    val bytes: Long,
    val withPictures: Boolean,
    val keptAt: Long,
    /** Wikipedia's revision when it was kept, for Outfitting's updates (O4); null when unknown. */
    val revision: Long?,
)

@Dao
interface KeptArticleDao {
    @Query("SELECT * FROM kept_articles ORDER BY keptAt DESC")
    fun observeAll(): Flow<List<KeptArticle>>

    @Query("SELECT * FROM kept_articles")
    suspend fun all(): List<KeptArticle>

    @Query("SELECT * FROM kept_articles WHERE lang = :lang AND title = :title")
    suspend fun get(lang: String, title: String): KeptArticle?

    @Upsert
    suspend fun put(article: KeptArticle)

    @Query("DELETE FROM kept_articles WHERE lang = :lang AND title = :title")
    suspend fun delete(lang: String, title: String)
}
