package app.granger.core.library

import app.granger.core.research.BestMatches
import app.granger.core.research.SourceKind
import app.granger.core.zim.ZimArchive
import app.granger.core.zim.ZimHit
import app.granger.core.zim.ZimItem
import app.granger.core.zim.ZimMetadata
import app.granger.core.zim.ZimOpenResult
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.io.writeString
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Plan I4b Task 9: Best matches' keyword side on the iPhone, the shared finder over packs alone. */
class PackPassageFinderIosTest {
    private val dir = NSTemporaryDirectory() + NSUUID().UUIDString
    private val packs = "$dir/Packs"

    /** A pack whose file holds its articles, one "path|title|text" a line; the first line is its uuid. */
    private class FakeArchive(lines: List<String>, val name: String) : ZimArchive {
        override val uuid = lines.first()
        private val articles = lines.drop(1).map { it.split('|') }
        override val metadata = ZimMetadata(name.replace('_', ' '), "", "eng", "2026-03-05", name)
        override val mainPath = articles.firstOrNull()?.get(0)
        override val hasFulltextIndex = true
        override fun resolvePath(path: String) = path.takeIf { p -> articles.any { it[0] == p } }
        override fun read(path: String) = articles.firstOrNull { it[0] == path }?.let {
            ZimItem(path, "text/html", "<h2 id=\"s\">${it[1]}</h2><p>${it[2]}</p>".encodeToByteArray())
        }
        override fun randomPath() = mainPath
        override fun suggest(query: String, limit: Int) =
            articles.filter { it[1].startsWith(query, ignoreCase = true) }.take(limit).map { ZimHit(it[0], it[1]) }
        override fun search(query: String, limit: Int) =
            articles.filter { it[2].contains(query, ignoreCase = true) }.take(limit).map { ZimHit(it[0], it[1], "", 100) }
        override fun close() {}
    }

    private fun write(name: String, vararg lines: String) {
        SystemFileSystem.createDirectories(Path(packs))
        SystemFileSystem.sink(Path("$packs/$name.zim")).buffered().use { it.writeString(lines.joinToString("\n")) }
    }

    private fun open(path: String): ZimOpenResult {
        val text = SystemFileSystem.source(Path(path)).buffered().use { it.readByteArray() }.decodeToString()
        return ZimOpenResult.Opened(FakeArchive(text.lines(), path.substringAfterLast('/').removeSuffix(".zim")))
    }

    @Test
    fun findsPassagesAndBestMatchesOverPacks() = runBlocking {
        write(
            "wikipedia_en_top", "uuid-wp",
            "A/Mars|Mars|Mars is the fourth planet from the Sun, a dusty and cold desert world with a thin atmosphere.",
            "A/Venus|Venus|Venus is the second planet from the Sun and the hottest planet in the Solar System.",
        )
        write("mars_guide", "uuid-mg", "A/Olympus|Olympus Mons|Olympus Mons is a very large volcano on Mars, the tallest in the Solar System.")
        val db = openLibraryDatabase("$dir/library.db")
        val library = PackLibrary(db, ::open, { listOf(packs) }, now = { 7L })
        library.refresh()

        val finder = PackPassageFinder(library)
        val found = finder.find(listOf("mars"))
        val articles = found.filter { it.kind == SourceKind.ARTICLE }
        assertEquals(setOf("A/Mars", "A/Olympus"), articles.map { it.location }.toSet())
        assertTrue(articles.first { it.location == "A/Mars" }.text.contains("fourth planet"))
        // The pack titled "mars guide" is a catalogue card: whole-word matching runs on the iPhone's regex too.
        assertEquals(listOf("uuid-mg"), found.filter { it.kind == SourceKind.CARD }.map { it.sourceId })
        assertTrue(found.none { it.location == "A/Venus" })
        assertTrue(finder.lastTimings!!.packMs.keys.containsAll(listOf("wikipedia_en_top", "mars_guide")))
        assertEquals(null, finder.entry("mars", listOf()))

        val best = BestMatches(finder = finder, dense = null, embed = null).stream("mars").toList().last()
        assertTrue(best.matches.any { it.passage.location == "A/Mars" })
        db.close()
    }
}
