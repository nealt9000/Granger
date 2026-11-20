package app.granger.online

import app.granger.core.library.KeptArticle
import app.granger.core.library.KeptArticleDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import app.granger.core.library.ConcurrentCache
import app.granger.core.text.hex
import app.granger.core.text.sha1
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.io.readString
import kotlinx.io.writeString

/**
 * Articles kept on this phone on purpose (spec §5, Plan O2 Task 3; builder's call 3): a row in `kept_articles` and a
 * folder under [dir] holding `page.html` and `pictures.txt` (the picture paths it uses). Pictures sit once in `img/`,
 * shared between kept articles, and go when the last article using them goes. Never removed by Granger.
 *
 * The rows are mirrored in memory so the reader's interceptor can ask without touching the database.
 */
class KeptStore(private val dir: String, private val dao: KeptArticleDao, scope: CoroutineScope, private val clock: () -> Long = { app.granger.screens.nowMillis() }) {
    private val index = ConcurrentCache<String, KeptArticle>()

    val articles: Flow<List<KeptArticle>> = dao.observeAll()

    init {
        scope.launch { dao.observeAll().collect { list -> index.replaceAll(list.associateBy { keyOf(it.lang, it.title) }) } }
    }

    fun get(lang: String, title: String): KeptArticle? = index[keyOf(lang, title)]

    /** The kept page, or null when [title] isn't kept (or its file has gone). */
    fun page(lang: String, title: String): ByteArray? =
        get(lang, title)?.let { read(Path(dir, it.path, PAGE)) }

    /** A kept picture by its reader path (`_img/<host>/<path>`), or null. */
    fun picture(path: String): ByteArray? = read(Path(imgDir, sha1Of(path)))

    fun hasPicture(path: String): Boolean = isFile(Path(imgDir, sha1Of(path)))

    /**
     * Keeps [page] with [pictures] (reader path → bytes): written to files first, then the row, so a row never points at
     * missing files. A text-only keep passes no pictures.
     */
    suspend fun keep(lang: String, title: String, page: ByteArray, pictures: Map<String, ByteArray>, withPictures: Boolean, qid: Long?, revision: Long?): KeptArticle {
        val rel = "$lang/${sha1Of(title)}"
        val folder = Path(dir, rel).also { SystemFileSystem.createDirectories(it) }
        write(Path(folder, PAGE), page)
        SystemFileSystem.createDirectories(imgDir)
        for ((path, bytes) in pictures) Path(imgDir, sha1Of(path)).let { if (!isFile(it)) write(it, bytes) }
        SystemFileSystem.sink(Path(folder, PICTURES)).buffered().use { it.writeString(pictures.keys.joinToString("\n")) }
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
        val folder = Path(dir, row.path)
        val mine = picturesOf(folder)
        deleteRecursively(folder)
        val others = dao.all().flatMapTo(HashSet()) { picturesOf(Path(dir, it.path)) }
        for (p in mine - others) runCatching { SystemFileSystem.delete(Path(imgDir, sha1Of(p)), mustExist = false) }
    }

    private fun picturesOf(folder: Path): Set<String> =
        runCatching { SystemFileSystem.source(Path(folder, PICTURES)).buffered().use { it.readString() }.lines().filter { it.isNotBlank() }.toSet() }
            .getOrDefault(emptySet())

    private val imgDir get() = Path(dir, "img")

    private fun read(p: Path): ByteArray? =
        if (!isFile(p)) null else runCatching { SystemFileSystem.source(p).buffered().use { it.readByteArray() } }.getOrNull()

    private fun write(p: Path, bytes: ByteArray) = SystemFileSystem.sink(p).buffered().use { it.write(bytes) }

    private fun isFile(p: Path) = SystemFileSystem.metadataOrNull(p)?.isRegularFile == true

    private fun deleteRecursively(p: Path) {
        val meta = SystemFileSystem.metadataOrNull(p) ?: return
        if (meta.isDirectory) SystemFileSystem.list(p).forEach(::deleteRecursively)
        runCatching { SystemFileSystem.delete(p, mustExist = false) }
    }

    private companion object {
        const val PAGE = "page.html"
        const val PICTURES = "pictures.txt"
        fun keyOf(lang: String, title: String) = "$lang:$title"

        /** The name a page's folder or a picture's file goes under (as `SavedStore.sha1` on Android: SHA-1 of the UTF-8, hex). */
        fun sha1Of(text: String) = sha1(text.encodeToByteArray()).hex()
    }
}
