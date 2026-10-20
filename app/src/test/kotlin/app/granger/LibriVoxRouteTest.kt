package app.granger

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.granger.archive.ArchiveContent
import app.granger.archive.ArchiveUi
import app.granger.core.archive.ArchiveKind
import app.granger.core.archive.ArchiveShelf
import app.granger.shell.RailTool
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LibriVoxRouteTest {
    @get:Rule val compose = createComposeRule()

    @Test fun spokenWordNoteOpensLibriVox() {
        val ui = ArchiveUi(
            kind = ArchiveKind.SPOKEN_WORD, shelf = ArchiveShelf.SPOKEN_MOST_DOWNLOADED, filtered = true, waiverNeeded = true,
            loading = false, filterYear = 1926,
        )
        lateinit var nav: NavHostController
        compose.setContent {
            nav = rememberNavController()
            NavHost(nav, startDestination = ArchiveRoute(ArchiveKind.SPOKEN_WORD)) {
                composable<ArchiveRoute> {
                    ArchiveContent(
                        ui = ui, searchText = "", snackbar = SnackbarHostState(),
                        onQuery = {}, onTab = {}, onShelf = {}, onRetry = {}, onLoadMore = {},
                        onOpenRow = {}, onGetRow = {}, onCancelRow = {}, onRetryRow = {}, onRemoveRow = {},
                        onAcceptWaiver = {}, onOpenLibriVox = { nav.openLibriVox() }, onBack = {},
                    )
                }
                composable<LibriVoxRoute> {}
            }
        }
        compose.onNodeWithText("LibriVox").performClick()
        compose.runOnIdle {
            val destination = nav.currentDestination!!
            assertThat(destination.hasRoute<LibriVoxRoute>()).isTrue()
            // It's a Library source, like Archive.org.
            assertThat(railToolOf(destination)).isEqualTo(RailTool.LIBRARY)
            // Back returns to the note.
            assertThat(nav.previousBackStackEntry?.destination?.hasRoute<ArchiveRoute>()).isTrue()
        }
    }
}
