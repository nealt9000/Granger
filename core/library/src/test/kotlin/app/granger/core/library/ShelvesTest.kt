package app.granger.core.library

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ShelvesTest {
    private val db = inMemoryDatabase()

    @After fun close() = db.close()

    private fun named(id: String, name: String) = pack(id, title = name).copy(name = name)

    private val top = named("u1", "wikipedia_en_top")
    private val medicine = named("m1", "wikipedia_en_medicine")
    private val ifixit = named("i1", "ifixit_en_all")

    private val bookshelf = StubBookshelf()

    private fun shelves(vararg packs: Pack): Pair<RoomShelves, StubLibrary> {
        val library = StubLibrary(packs.toList())
        return RoomShelves(db, library, bookshelf) to library
    }

    @Test fun packsWithoutAPlacementAreFiledOnFirstRead() = runTest {
        val (s, _) = shelves(top, medicine, ifixit)
        val shelf = s.shelves.first().single()
        assertThat(shelf.mediaType.id).isEqualTo("literature")
        assertThat(shelf.collections.map { it.collection.name }).containsExactly("iFixit", "Wikipedia").inOrder()
        assertThat(shelf.collections.map { it.packs.size }).containsExactly(1, 2).inOrder()
        // A second read finds nothing more to file.
        val placements = db.shelfDao().getPlacements()
        assertThat(s.shelves.first().single().collections).hasSize(2)
        assertThat(db.shelfDao().getPlacements()).containsExactlyElementsIn(placements)
    }

    @Test fun blankNameFilesUnderOther() = runTest {
        val (s, _) = shelves(pack("x1"))
        val row = s.shelves.first().single().collections.single()
        assertThat(row.collection.name).isEqualTo("Other")
        assertThat(row.packs.map { it.id }).containsExactly("x1")
    }

    @Test fun movedPackKeepsItsPlaceAfterUpdate() = runTest {
        val (s, library) = shelves(top)
        s.shelves.first()
        val mine = s.createCollection("Mine", "literature")
        s.movePack(top, mine)
        library.state.value = listOf(named("u2", "wikipedia_en_top"))
        val shelf = s.shelves.first().single()
        val row = shelf.collections.single()
        assertThat(row.collection.id).isEqualTo(mine)
        assertThat(row.packs.map { it.id }).containsExactly("u2")
        assertThat(s.collection(mine).first()!!.packs.map { it.id }).containsExactly("u2")
    }

    @Test fun moveCollectionToNewMediaType() = runTest {
        val (s, _) = shelves(top, ifixit)
        val wikipedia = s.shelves.first().single().collections.first { it.collection.name == "Wikipedia" }.collection.id
        val reference = s.createMediaType("Reference")
        s.moveCollection(wikipedia, reference)
        val shelves = s.shelves.first()
        assertThat(shelves.map { it.mediaType.name }).containsExactly("Literature", "Reference").inOrder()
        assertThat(shelves[1].collections.single().collection.name).isEqualTo("Wikipedia")
    }

    @Test fun builtInMediaTypeCannotBeDeleted() = runTest {
        val (s, _) = shelves()
        assertThat(s.deleteMediaType("music")).isFalse()
        assertThat(db.shelfDao().getMediaTypes().map { it.id }).contains("music")
    }

    @Test fun nonEmptyUserMediaTypeCannotBeDeleted() = runTest {
        val (s, _) = shelves(top)
        val wikipedia = s.shelves.first().single().collections.single().collection.id
        val reference = s.createMediaType("Reference")
        s.moveCollection(wikipedia, reference)
        assertThat(s.deleteMediaType(reference)).isFalse()
        val empty = s.createMediaType("Empty")
        assertThat(s.deleteMediaType(empty)).isTrue()
    }

    @Test fun deletingACollectionRefilesItsPacks() = runTest {
        val (s, _) = shelves(top)
        s.shelves.first()
        val mine = s.createCollection("Mine", "literature")
        s.movePack(top, mine)
        s.deleteCollection(mine)
        val row = s.shelves.first().single().collections.single()
        assertThat(row.collection.name).isEqualTo("Wikipedia")
        assertThat(row.packs.map { it.id }).containsExactly("u1")
        assertThat(s.collection(mine).first()).isNull()
    }

    @Test fun emptyMediaTypesAreHidden() = runTest {
        val (s, _) = shelves(top)
        assertThat(s.shelves.first().map { it.mediaType.id }).containsExactly("literature")
    }

    @Test fun booksAndPacksShareACollection() = runTest {
        val (s, _) = shelves(named("z1", "granger_classics_en"))
        bookshelf.state.value = listOf(book("pride"))
        val row = s.shelves.first().single().collections.single()
        assertThat(row.collection.defaultKey).isEqualTo("granger-classics")
        assertThat(row.collection.name).isEqualTo("Granger Classics")
        assertThat(row.packs.map { it.id }).containsExactly("z1")
        assertThat(row.books.map { it.id }).containsExactly(BookIds.of(BookOrigin.GRANGER, "pride"))
    }

    @Test fun importsFileUnderMyBooks() = runTest {
        val (s, _) = shelves()
        bookshelf.state.value = listOf(book("abc", BookOrigin.IMPORT), book("ia1", BookOrigin.ARCHIVE_ORG))
        // A shelf holding only books is shown.
        val shelf = s.shelves.first().single()
        assertThat(shelf.mediaType.id).isEqualTo("literature")
        assertThat(shelf.collections.map { it.collection.name }).containsExactly("Archive.org", "My books").inOrder()
        val mine = shelf.collections.first { it.collection.defaultKey == "my-books" }
        assertThat(mine.books.map { it.sourceRef }).containsExactly("abc")
        assertThat(mine.packs).isEmpty()
    }

    @Test fun movedBookKeepsItsPlace() = runTest {
        val (s, _) = shelves()
        val pride = book("pride")
        bookshelf.state.value = listOf(pride)
        s.shelves.first()
        val mine = s.createCollection("Mine", "literature")
        s.moveBook(pride, mine)
        // A re-download changes the file but not the id.
        bookshelf.state.value = listOf(pride.copy(filePath = "/books/pride_2026-11-01.epub", title = "Pride (revised)"))
        val row = s.shelves.first().single().collections.single()
        assertThat(row.collection.id).isEqualTo(mine)
        assertThat(row.books.map { it.title }).containsExactly("Pride (revised)")
        assertThat(s.collection(mine).first()!!.books.map { it.id }).containsExactly(pride.id)
        // A collection holding a book isn't empty, so its media type can't be deleted.
        val reference = s.createMediaType("Reference")
        s.moveCollection(mine, reference)
        assertThat(s.deleteMediaType(reference)).isFalse()
        // Deleting the collection files the book back by origin.
        s.deleteCollection(mine)
        val back = s.shelves.first().single().collections.single()
        assertThat(back.collection.defaultKey).isEqualTo("granger-classics")
        assertThat(back.books.map { it.id }).containsExactly(pride.id)
    }

    @Test fun filingRules() {
        assertThat(Filing.defaultKey(named("a", "wikipedia_en_medicine"))).isEqualTo("wikipedia")
        assertThat(Filing.defaultKey(named("a", "granger_classics"))).isEqualTo("granger-classics")
        assertThat(Filing.defaultKey(named("a", "askubuntu.com_en_stackexchange"))).isEqualTo("stack-exchange")
        assertThat(Filing.defaultKey(named("a", "zimgit"))).isEqualTo("pack:zimgit")
        assertThat(Filing.packKey(pack("a"))).isEqualTo("a")
        assertThat(Filing.bookKey(book("x"))).isEqualTo("book:granger:x")
        assertThat(Filing.defaultKey(book("x", BookOrigin.IMPORT))).isEqualTo("my-books")
        assertThat(Filing.defaultKey(book("x", BookOrigin.ARCHIVE_ORG))).isEqualTo("archive-org")
        assertThat(Filing.isSuperseded(named("a", "granger_classics_en"))).isTrue()
        assertThat(Filing.isSuperseded(named("a", "gutenberg_en_all"))).isFalse()
    }
}
