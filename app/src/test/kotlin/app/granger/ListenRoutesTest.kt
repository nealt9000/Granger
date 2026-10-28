package app.granger

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.granger.core.archive.ArchiveKind
import app.granger.shell.RailTool
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Every music, audiobook and audio-source route lights Listen; the Library keeps text and maps (Plan U2 Decision 4). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ListenRoutesTest {
    @get:Rule val compose = createComposeRule()

    @Test fun audioRoutesLightListen() {
        lateinit var nav: NavHostController
        compose.setContent {
            nav = rememberNavController()
            NavHost(nav, startDestination = LibraryRoute()) {
                composable<LibraryRoute> {}
                composable<CollectionRoute> {}
                composable<ListenRoute> {}
                composable<ListenPlayerRoute> {}
                composable<MusicRoute> {}
                composable<AlbumRoute> {}
                composable<ArtistRoute> {}
                composable<GenreRoute> {}
                composable<PlaylistRoute> {}
                composable<FolderRoute> {}
                composable<AudiobookShelfRoute> {}
                composable<AudiobookRoute> {}
                composable<LibriVoxRoute> {}
                composable<ArchiveRoute> {}
                composable<GetMoreRoute> {}
                composable<MediaSettingsRoute> {}
            }
        }
        fun toolAt(route: Any, archiveKind: ArchiveKind? = null): RailTool? {
            compose.runOnIdle { nav.navigate(route) }
            return compose.runOnIdle { railToolOf(nav.currentDestination!!, archiveKind = archiveKind) }
        }
        val listen = listOf(
            ListenRoute, ListenPlayerRoute, MusicRoute(), MusicRoute("c:phone"), AlbumRoute("album:Blue"), ArtistRoute("Miles Davis"),
            GenreRoute("Jazz"), PlaylistRoute(1L), FolderRoute("Music"), AudiobookShelfRoute(), AudiobookRoute("audiobook:phone:t"), LibriVoxRoute,
        )
        for (route in listen) assertThat(toolAt(route)).isEqualTo(RailTool.LISTEN)
        assertThat(toolAt(ArchiveRoute(ArchiveKind.SPOKEN_WORD), ArchiveKind.SPOKEN_WORD)).isEqualTo(RailTool.LISTEN)
        assertThat(toolAt(ArchiveRoute(ArchiveKind.MUSIC), ArchiveKind.MUSIC)).isEqualTo(RailTool.LISTEN)
        // Archive.org's books, Get more, collections and the Library stay the Library's; the phone's music settings, Settings'.
        assertThat(toolAt(ArchiveRoute(ArchiveKind.TEXTS), ArchiveKind.TEXTS)).isEqualTo(RailTool.LIBRARY)
        assertThat(toolAt(GetMoreRoute)).isEqualTo(RailTool.LIBRARY)
        assertThat(toolAt(CollectionRoute("c:gc"))).isEqualTo(RailTool.LIBRARY)
        assertThat(toolAt(LibraryRoute())).isEqualTo(RailTool.LIBRARY)
        assertThat(toolAt(MediaSettingsRoute)).isEqualTo(RailTool.SETTINGS)
    }
}
