package app.granger.home

import app.granger.catalogOf
import app.granger.core.library.AlbumSummary
import app.granger.core.library.CollectionRow
import app.granger.core.library.MediaType
import app.granger.core.library.PackStatus
import app.granger.core.library.Shelf
import app.granger.core.library.ShelfCollection
import app.granger.pack
import app.granger.packEntry
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FeedScopeTest {
    private val wikipedia = pack("u1", "Wikipedia").copy(name = "wikipedia_en_all")
    private val wikiquote = pack("u2", "Wikiquote").copy(name = "wikiquote_en_all")
    private val ifixit = pack("u3", "iFixit").copy(name = "ifixit_en_all")
    private val missing = pack("u4", "Gone", status = PackStatus.MISSING).copy(name = "wikivoyage_en_all")
    private val classics = ShelfCollection("c:granger-classics", "Granger Classics", "literature", userCreated = false, defaultKey = "granger-classics")
    private val shelf = Shelf(
        MediaType("literature", "Literature", builtIn = true, sortOrder = 0),
        listOf(CollectionRow(classics, packs = emptyList(), books = listOf(app.granger.book("book:granger:1", "Hamlet")))),
    )
    private val album = AlbumSummary("album:Blue", "Blue", "Joni", "1971", "", 10, 60_000, null, PackStatus.OK)

    private fun snapshot(catalog: app.granger.core.catalog.Catalog? = null) =
        LibrarySnapshot(listOf(wikipedia, wikiquote, ifixit, missing), shelf.allCollections.flatMap { it.books }, listOf(shelf), listOf(album), emptyList(), catalog)

    @Test fun parsesTheGrammarAndRoundTrips() {
        val scopes = listOf("library", "pack:wikipedia_en_all", "family:wikiquote", "collection:c:granger-classics", "music", "audiobooks")
        for (text in scopes) assertThat(FeedScope.parse(text)?.text).isEqualTo(text)
        assertThat(FeedScope.parse("pack:")).isNull()
        assertThat(FeedScope.parse("digest:research")).isNull()
        assertThat(FeedScope.parse("")).isNull()
    }

    @Test fun aPackByNameAndAFamilyByPrefixWhenTheCatalogIsNotThere() {
        assertThat(FeedScope.parse("pack:ifixit_en_all")!!.resolve(snapshot())!!.packs).containsExactly(ifixit)
        assertThat(FeedScope.parse("family:wikiquote")!!.resolve(snapshot())!!.name).isEqualTo("Wikiquote")
        assertThat(FeedScope.parse("family:wikivoyage")!!.resolve(snapshot())).isNull() // Only whole packs count.
        assertThat(FeedScope.parse("family:gutenberg")!!.resolve(snapshot())).isNull()
    }

    @Test fun theCatalogsFamilyWinsOverThePrefix() {
        val catalog = catalogOf(packs = listOf(packEntry("ifixit_en_all", zimName = "ifixit_en_all").copy(family = "repair")))
        assertThat(familyOf(ifixit, catalog)).isEqualTo("repair")
        assertThat(familyOf(wikipedia, catalog)).isEqualTo("wikipedia")
        assertThat(FeedScope.Family("repair").resolve(snapshot(catalog))!!.packs).containsExactly(ifixit)
    }

    @Test fun collectionsMusicAndTheLibraryResolveToTheirItems() {
        val classics = FeedScope.parse("collection:c:granger-classics")!!.resolve(snapshot())!!
        assertThat(classics.name).isEqualTo("Granger Classics")
        assertThat(classics.books.map { it.title }).containsExactly("Hamlet")
        assertThat(FeedScope.parse("collection:c:nope")!!.resolve(snapshot())).isNull()
        assertThat(FeedScope.Music.resolve(snapshot())!!.albums).containsExactly(album)
        assertThat(FeedScope.Audiobooks.resolve(snapshot())).isNull()
        val library = FeedScope.Library.resolve(snapshot())!!
        assertThat(library.packs).containsExactly(wikipedia, wikiquote, ifixit)
        assertThat(library.books).hasSize(1)
    }
}
