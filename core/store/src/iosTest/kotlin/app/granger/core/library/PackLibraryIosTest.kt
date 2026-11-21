package app.granger.core.library

import app.granger.core.zim.ZimArchive
import app.granger.core.zim.ZimHit
import app.granger.core.zim.ZimItem
import app.granger.core.zim.ZimMetadata
import app.granger.core.zim.ZimOpenResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.io.buffered
import kotlinx.io.readByteArray
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Plan I4 Task 3: pack rows and search across packs on the iPhone, over any [ZimArchive]. */
class PackLibraryIosTest {
    private val dir = NSTemporaryDirectory() + NSUUID().UUIDString
    private val packs = "$dir/Packs"

    /** A pack whose file holds its title list, one "path|title" a line; the first line is its uuid. */
    private class FakeArchive(lines: List<String>, val name: String) : ZimArchive {
        override val uuid = lines.first()
        private val titles = lines.drop(1).map { it.substringBefore('|') to it.substringAfter('|') }
        override val metadata = ZimMetadata(name.replace('_', ' '), "", "eng", "2026-03-05", name)
        override val mainPath = titles.firstOrNull()?.first
        override val hasFulltextIndex = true
        override fun resolvePath(path: String) = path.takeIf { p -> titles.any { it.first == p } }
        override fun read(path: String) = resolvePath(path)?.let { ZimItem(it, "text/html", "<p>$it</p>".encodeToByteArray()) }
        override fun randomPath() = mainPath
        override fun suggest(query: String, limit: Int) =
            titles.filter { it.second.startsWith(query, ignoreCase = true) }.take(limit).map { ZimHit(it.first, it.second) }
        override fun search(query: String, limit: Int) =
            titles.filter { it.second.contains(query, ignoreCase = true) }.take(limit).map { ZimHit(it.first, it.second, "<b>$query</b>", 1) }
        override fun close() {}
    }

    private fun write(name: String, vararg lines: String) {
        SystemFileSystem.createDirectories(Path(packs))
        SystemFileSystem.sink(Path("$packs/$name.zim")).buffered().use { it.writeString(lines.joinToString("\n")) }
    }

    private fun open(path: String): ZimOpenResult {
        val text = SystemFileSystem.source(Path(path)).buffered().use { it.readByteArray() }.decodeToString()
        if (text.isBlank()) return ZimOpenResult.Failed("empty")
        return ZimOpenResult.Opened(FakeArchive(text.lines(), path.substringAfterLast('/').removeSuffix(".zim")))
    }

    @Test
    fun scansOpensAndSearches() = runBlocking {
        write("wikipedia_en_top", "uuid-wp", "A/Moon|Moon", "A/Mars|Mars", "A/Moonlight|Moonlight")
        write("wikivoyage_en", "uuid-wv", "A/Moon_Lake|Moon Lake")
        write("broken")
        val db = openLibraryDatabase("$dir/library.db")
        val library = PackLibrary(db, ::open, { listOf(packs) }, now = { 7L })
        library.refresh()
        val rows = library.packs.first()
        assertEquals(3, rows.size)
        assertEquals(PackStatus.CORRUPT, rows.single { it.id !in setOf("uuid-wp", "uuid-wv") }.status)
        assertNotNull(library.archive("uuid-wp"))
        assertEquals("A/Moon", library.startPath("uuid-wp"))

        val search = PackSearch(library)
        val titles = search.titles("moon").hits
        assertEquals("Moon", titles.first().title)
        assertTrue(titles.any { it.packId == "uuid-wv" })
        assertTrue(search.fullText("Mars").any { it.path == "A/Mars" })

        assertTrue(library.remove("uuid-wv"))
        assertTrue(!SystemFileSystem.exists(Path("$packs/wikivoyage_en.zim")))
        assertEquals(2, library.packs.first().size)
        db.close()
    }
}
