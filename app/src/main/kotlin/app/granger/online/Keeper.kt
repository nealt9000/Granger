package app.granger.online

import app.granger.core.library.KeptArticle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Keeps and removes online articles (Plan O2 Tasks 3 and 5): the page and, under Offline pictures = Save pictures, its
 * pictures, taken from Saved for now where they are and fetched where they aren't, then written to the [kept] store.
 */
class Keeper(
    private val archive: (lang: String) -> OnlineWikipediaArchive?,
    private val kept: KeptStore,
    /** Offline pictures: true keeps pictures (the default), false keeps text only. */
    private val withPictures: () -> Boolean,
    /** Something was kept or removed: the marks are worked out again. */
    private val changed: () -> Unit = {},
) {
    /** Keeps [title]; null when its page can't be had (no signal and nothing saved). */
    suspend fun keep(lang: String, title: String): KeptArticle? = withContext(Dispatchers.IO) {
        val a = archive(lang) ?: return@withContext null
        val pictures = withPictures()
        val content = a.keepContent(title, pictures) ?: return@withContext null
        val summary = runCatching { a.summary(title) }.getOrNull()
        kept.keep(lang, title, content.page, content.pictures, pictures, summary?.qid, summary?.revision).also { changed() }
    }

    suspend fun remove(lang: String, title: String) = withContext(Dispatchers.IO) { kept.remove(lang, title); changed() }

    fun isKept(lang: String, title: String): KeptArticle? = kept.get(lang, title)
}
