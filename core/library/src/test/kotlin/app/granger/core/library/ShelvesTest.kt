package app.granger.core.library

import app.granger.core.zim.ZimArchive
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private class StubLibrary(initial: List<Pack>) : Library {
    val state = MutableStateFlow(initial)
    override val packs: Flow<List<Pack>> = state
    override val bookmarks: Flow<List<Bookmark>> = MutableStateFlow(emptyList())
    override suspend fun refresh() = Unit
    override suspend fun fileReplaced(path: String) = Unit
    override fun archive(packId: String): ZimArchive? = null
    override fun cachedArchive(packId: String): ZimArchive? = null
    override suspend fun startPath(packId: String): String? = null
    override fun isBookmarked(packId: String, path: String): Flow<Boolean> = MutableStateFlow(false)
    override suspend fun setBookmarked(packId: String, path: String, title: String, bookmarked: Boolean) = Unit
    override suspend fun savePosition(packId: String, path: String, scrollFraction: Float) = Unit
    override suspend fun position(packId: String, path: String): Float? = null
    override suspend fun remove(packId: String): Boolean = true
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ShelvesTest {
    private val db = inMemoryDatabase()

    @After fun close() = db.close()

    private fun named(id: String, name: String) = pack(id, title = name).copy(name = name)

    private val top = named("u1", "wikipedia_en_top")
    private val medicine = named("m1", "wikipedia_en_medicine")
    private val ifixit = named("i1", "ifixit_en_all")

    private fun shelves(vararg packs: Pack): Pair<RoomShelves, StubLibrary> {
        val library = StubLibrary(packs.toList())
        return RoomShelves(db, library) to library
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

    @Test fun filingRules() {
        assertThat(Filing.defaultKey(named("a", "wikipedia_en_medicine"))).isEqualTo("wikipedia")
        assertThat(Filing.defaultKey(named("a", "granger_classics"))).isEqualTo("granger-classics")
        assertThat(Filing.defaultKey(named("a", "askubuntu.com_en_stackexchange"))).isEqualTo("stack-exchange")
        assertThat(Filing.defaultKey(named("a", "zimgit"))).isEqualTo("pack:zimgit")
        assertThat(Filing.packKey(pack("a"))).isEqualTo("a")
    }
}
