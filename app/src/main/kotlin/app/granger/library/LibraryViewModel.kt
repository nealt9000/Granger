package app.granger.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.granger.core.library.Library
import app.granger.core.library.Shelf
import app.granger.core.library.Shelves
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** An archive source the Library links to. The three archive.org links are its texts, spoken-word and music views. */
enum class SourceLink(val title: String) {
    GRANGER("Granger"), KIWIX("Kiwix"), ARCHIVE_ORG("Archive.org"),
    LIBRIVOX("LibriVox"), ARCHIVE_ORG_SPOKEN("Archive.org"), ARCHIVE_ORG_MUSIC("Archive.org"),
}

/** The archive sources of one media type, under its header (spec §3.4). */
data class SourceGroup(val title: String /* "Literature" | "Audiobooks" | "Music" */, val links: List<SourceLink>)

/** The Library's two sections: [media] (media types with their collections) and the archive [sources] by media type. */
data class LibraryUi(val media: List<Shelf>, val sources: List<SourceGroup>, val emptyLibrary: Boolean)

/** Every archive source, grouped by the media type it brings. */
val SOURCE_GROUPS = listOf(
    SourceGroup("Literature", listOf(SourceLink.GRANGER, SourceLink.KIWIX, SourceLink.ARCHIVE_ORG)),
    SourceGroup("Audiobooks", listOf(SourceLink.LIBRIVOX, SourceLink.ARCHIVE_ORG_SPOKEN)),
    SourceGroup("Music", listOf(SourceLink.ARCHIVE_ORG_MUSIC)),
)

class LibraryViewModel(
    shelves: Shelves,
    private val library: Library,
    private val importer: ImportRunner,
    private val appScope: CoroutineScope,
) : ViewModel() {
    private val _notice = MutableStateFlow<String?>(null)

    /** What the last import did, shown once as a snackbar. */
    val notice: StateFlow<String?> = _notice

    fun dismissNotice() {
        _notice.value = null
    }

    /** Imports the chosen files one by one. Runs in the app scope so leaving the screen doesn't stop it. */
    fun import(uris: List<Uri>) {
        if (uris.isEmpty()) return
        appScope.launch {
            _notice.value = importer.run(uris).text()
        }
    }

    /** Null until the first load finishes. */
    val ui: StateFlow<LibraryUi?> = shelves.shelves
        .map { LibraryUi(it, SOURCE_GROUPS, emptyLibrary = it.isEmpty()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Re-scan pack folders; called whenever the screen resumes (an SD card may have come back). */
    fun refresh() {
        viewModelScope.launch { library.refresh() }
    }
}
