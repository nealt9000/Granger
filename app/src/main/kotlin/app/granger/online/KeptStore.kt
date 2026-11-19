package app.granger.online

import app.granger.core.library.KeptArticle
import app.granger.core.library.KeptArticleDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Articles kept on this phone on purpose (spec §5, Plan O2 Task 3; builder's call 3): a row in `kept_articles` and a
 * folder under [dir] holding `page.html` and `pictures.txt` (the picture paths it uses). Pictures sit once in `img/`,
 * shared between kept articles, and go when the last article using them goes. Never removed by Granger.
 *
 * The rows are mirrored in memory so the reader's interceptor can ask without touching the database.
 */
class KeptStore(private val dir: File, private val dao: KeptArticleDao, scope: CoroutineScope, private val clock: () -> Long = System::currentTimeMillis) {
    private val index = ConcurrentHashMap<String, KeptArticle>()

    val articles: Flow<List<KeptArticle>> = dao.observeAll()

    init {
        scope.launch { dao.observeAll().collect { list -> index.keys.retainAll(list.mapTo(HashSet()) { keyOf(it.lang, it.title) }); list.forEach { index[keyOf(it.lang, it.title)] = it } } }
    }

    fun get(lang: String, title: String): KeptArticle? = index[keyOf(lang, title)]

    /** The kept page, or null when [title] isn't kept (or its file has gone). */
    fun page(lang: String, title: String): ByteArray? =
        get(lang, title)?.let { runCatching { File(File(dir, it.path), PAGE).readBytes() }.getOrNull() }

    /** A kept picture by its reader path (`_img/<host>/<path>`), or null. */
    fun picture(path: String): ByteArray? = File(imgDir, SavedStore.sha1(path)).takeIf { it.isFile }?.let { runCatching { it.readBytes() }.getOrNull() }

    fun hasPicture(path: String): Boolean = File(imgDir, SavedStore.sha1(path)).isFile

    /**
     * Keeps [page] with [pictures] (reader path → bytes): written to files first, then the row, so a row never points at
     * missing files. A text-only keep passes no pictures.
     */
    suspend fun keep(lang: String, title: String, page: ByteArray, pictures: Map<String, ByteArray>, withPictures: Boolean, qid: Long?, revision: Long?): KeptArticle {
        val rel = "$lang/${SavedStore.sha1(title)}"
        val folder = File(dir, rel).apply { mkdirs() }
        File(folder, PAGE).writeBytes(page)
        imgDir.mkdirs()
        for ((path, bytes) in pictures) File(imgDir, SavedStore.sha1(path)).let { if (!it.isFile) it.writeBytes(bytes) }
        File(folder, PICTURES).writeText(pictures.keys.joinToString("\n"))
        val row = KeptArticle(lang, title, qid, rel, page.size.toLong() + pictures.values.sumOf { it.size.toLong() }, withPictures, clock(), revision)
        dao.put(row)
        index[keyOf(lang, title)] = row
        return row
    }

    /** Removes a kept article, and the pictures no other kept article uses. */
    suspend fun remove(lang: String, title: String) {
        val row = dao.get(lang, title) ?: return
        dao.delete(lang, title)
        index.remove(keyOf(lang, title))
        val folder = File(dir, row.path)
        val mine = picturesOf(folder)
        folder.deleteRecursively()
        val others = dao.all().flatMapTo(HashSet()) { picturesOf(File(dir, it.path)) }
        for (p in mine - others) File(imgDir, SavedStore.sha1(p)).delete()
    }

    private fun picturesOf(folder: File): Set<String> =
        runCatching { File(folder, PICTURES).readLines().filter { it.isNotBlank() }.toSet() }.getOrDefault(emptySet())

    private val imgDir get() = File(dir, "img")

    private companion object {
        const val PAGE = "page.html"
        const val PICTURES = "pictures.txt"
        fun keyOf(lang: String, title: String) = "$lang:$title"
    }
}
