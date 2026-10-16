package app.granger.library

import app.granger.FakeLibrary
import app.granger.FakeReadingSessions
import app.granger.FakeShelves
import app.granger.MainDispatcherRule
import app.granger.pack
import app.granger.core.library.CollectionRow
import app.granger.core.library.MediaType
import app.granger.core.library.PackStatus
import app.granger.core.library.Shelf
import app.granger.core.library.ShelfCollection
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class CollectionViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val library = FakeLibrary()
    private val shelves = FakeShelves()
    private val sessions = FakeReadingSessions()

    private fun collection(id: String, userCreated: Boolean, vararg packs: app.granger.core.library.Pack) = CollectionRow(
        ShelfCollection(id, id, "lit", userCreated, defaultKey = if (userCreated) null else id),
        packs.toList(),
    )

    private fun show(vararg rows: CollectionRow) {
        shelves.shelfList.value = listOf(Shelf(MediaType("lit", "Literature", true, 0), rows.toList()))
    }

    private fun vm(id: String) = CollectionViewModel(id, shelves, library, sessions)

    @Test fun openStartsATabAtTheLastReadPath() = runTest(main.dispatcher) {
        val p = pack("wiki", "Wikipedia")
        show(collection("c1", false, p))
        library.lastRead["wiki"] = "A/Ada"
        val tab = vm("c1").open(p)
        assertThat(tab).isNotNull()
        val opened = sessions.tab(tab!!)!!
        assertThat(opened.packId).isEqualTo("wiki")
        assertThat(opened.path).isEqualTo("A/Ada")
        assertThat(opened.title).isEqualTo("Wikipedia")
    }

    @Test fun openUnavailablePackDoesNothing() = runTest(main.dispatcher) {
        val missing = pack("gone", status = PackStatus.MISSING)
        val noStart = pack("blank")
        show(collection("c1", false, missing, noStart))
        val vm = vm("c1")
        assertThat(vm.open(missing)).isNull()
        assertThat(vm.open(noStart)).isNull()
        assertThat(sessions.tabs.value).isEmpty()
    }

    @Test fun renameAndDeleteOnlyForUserCollections() = runTest(main.dispatcher) {
        show(collection("built", false, pack("a")), collection("mine", true, pack("b")))
        assertThat(vm("built").ui.filterNotNull().first().collection.userCreated).isFalse()
        assertThat(vm("mine").ui.filterNotNull().first().collection.userCreated).isTrue()
    }

    @Test fun removeDeletesThePackFromThePhone() = runTest(main.dispatcher) {
        val p = pack("a")
        show(collection("c1", false, p))
        vm("c1").remove(p)
        testScheduler.advanceUntilIdle()
        assertThat(library.removed).containsExactly("a")
    }

    @Test fun movePackPassesThroughToShelves() = runTest(main.dispatcher) {
        val p = pack("a")
        show(collection("c1", false, p))
        vm("c1").movePack(p, "c2")
        testScheduler.advanceUntilIdle()
        assertThat(shelves.moves).containsExactly("a" to "c2")
    }
}
