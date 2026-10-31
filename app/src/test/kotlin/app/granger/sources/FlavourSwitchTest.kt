package app.granger.sources

import app.granger.FakeDownloads
import app.granger.FakeLibrary
import app.granger.catalogOf
import app.granger.core.downloads.DownloadState
import app.granger.core.library.Bookmark
import app.granger.pack
import app.granger.packEntry
import app.granger.record
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** Plan KB walkthrough: Switch removes the old flavour once the new one has installed, keeping its marks. */
class FlavourSwitchTest {
    private val nopic = packEntry("med_nopic", zimName = "wikipedia_en_medicine").copy(flavour = "nopic")
    private val maxi = packEntry("med_maxi", zimName = "wikipedia_en_medicine").copy(flavour = "maxi")
    private val other = packEntry("top_maxi", zimName = "wikipedia_en_top").copy(flavour = "maxi")
    private val catalog = catalogOf(listOf(nopic, maxi, other))
    private val downloads = FakeDownloads()
    private val library = FakeLibrary()

    private fun onPhone(vararg entries: Pair<String, DownloadState>) {
        downloads.list.value = entries.map { (id, state) -> record(id, state) }
        library.packList.value = entries.filter { it.second == DownloadState.INSTALLED }.map { pack("u-${it.first}") }
    }

    @Test fun theNewFlavourInstalledTakesTheOldOnesPlace() = runTest {
        onPhone(nopic.id to DownloadState.INSTALLED, maxi.id to DownloadState.INSTALLED, other.id to DownloadState.INSTALLED)
        library.bookmarkList.value = listOf(Bookmark("u-med_nopic", "Malaria", "Malaria", 1L))

        val removed = FlavourSwitch.settle(maxi.id, catalog, downloads, library)

        assertThat(removed).containsExactly(nopic.id)
        assertThat(library.superseded).containsExactly("u-med_nopic" to "u-med_maxi")
        assertThat(downloads.calls).containsExactly("remove:${nopic.id}")
        // Another pack is left alone; the bookmark lives on in the new flavour.
        assertThat(library.packs.first().map { it.id }).containsExactly("u-med_maxi", "u-top_maxi")
        assertThat(library.bookmarks.first().map { it.packId }).containsExactly("u-med_maxi")
    }

    @Test fun nothingGoesUntilTheNewFlavourIsOnThePhone() = runTest {
        // The new download failed, or was cancelled (its record gone): the old flavour stays.
        onPhone(nopic.id to DownloadState.INSTALLED, maxi.id to DownloadState.FAILED)
        assertThat(FlavourSwitch.settle(maxi.id, catalog, downloads, library)).isEmpty()
        onPhone(nopic.id to DownloadState.INSTALLED)
        assertThat(FlavourSwitch.settle(maxi.id, catalog, downloads, library)).isEmpty()
        assertThat(library.superseded).isEmpty()
        assertThat(downloads.calls).isEmpty()
        assertThat(library.packs.first().map { it.id }).containsExactly("u-med_nopic")
    }

    @Test fun ifTheLibraryCantSwapThemTheOldDownloadStays() = runTest {
        onPhone(nopic.id to DownloadState.INSTALLED, maxi.id to DownloadState.INSTALLED)
        library.supersedes = false
        assertThat(FlavourSwitch.settle(maxi.id, catalog, downloads, library)).isEmpty()
        assertThat(downloads.calls).isEmpty()
    }

    @Test fun aPackWithNoOtherFlavourIsLeftAlone() = runTest {
        onPhone(other.id to DownloadState.INSTALLED)
        assertThat(FlavourSwitch.settle(other.id, catalog, downloads, library)).isEmpty()
        assertThat(FlavourSwitch.settle("unknown", catalog, downloads, library)).isEmpty()
        assertThat(FlavourSwitch.settle(other.id, null, downloads, library)).isEmpty()
    }

    /** MDWiki's unflavoured ZIM shares its name with its flavour with pictures but is a pack of its own: both stay. */
    @Test fun anUnflavouredZimOfTheSameNameIsLeftAlone() = runTest {
        val plain = packEntry("mdwiki_en_all", zimName = "mdwiki_en_all")
        val pictures = packEntry("mdwiki_en_all_maxi", zimName = "mdwiki_en_all").copy(flavour = "maxi")
        val both = catalogOf(listOf(plain, pictures))
        onPhone(plain.id to DownloadState.INSTALLED, pictures.id to DownloadState.INSTALLED)
        assertThat(FlavourSwitch.settle(pictures.id, both, downloads, library)).isEmpty()
        assertThat(FlavourSwitch.settle(plain.id, both, downloads, library)).isEmpty()
        assertThat(library.superseded).isEmpty()
    }
}
