package app.granger.downloads

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.granger.DownloadsRoute
import app.granger.MainActivity
import app.granger.core.downloads.DOWNLOADS_LINK
import app.granger.core.downloads.DownloadState
import app.granger.railToolOf
import app.granger.shell.RailTool
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h800dp")
class DownloadsScreenTest {
    @get:Rule val compose = createComposeRule()

    private val calls = mutableListOf<String>()

    private val running = DownloadRow("item:wiki", "Wikipedia", RowState.RUNNING, 500, 1_000, itemIds = listOf("wiki"))
    private val failed = DownloadRow("item:emma", "Emma", RowState.FAILED, 0, 1_000, itemIds = listOf("emma"), error = "No connection.")
    private val done = DownloadRow("item:owls", "Owls", RowState.DONE, 1_000, 1_000, itemIds = listOf("owls"), open = OpenTarget.Pack("owls"))
    private val setup = DownloadRow(
        "group:onboarding:1", "Setting up", RowState.QUEUED, 0, 2_000, groupId = "onboarding:1",
        files = listOf(DownloadFile("Pack a", DownloadState.INSTALLED), DownloadFile("Pack b", DownloadState.QUEUED)),
    )

    private fun show(ui: DownloadsUi) = compose.setContent {
        DownloadsContent(
            ui, onBack = {},
            onCancel = { calls += "cancel:${it.key}" },
            onRetry = { calls += "retry:${it.key}" },
            onOpen = { calls += "open:${it.key}" },
            onGetMore = { calls += "more" },
        )
    }

    @Test fun eachSectionHasItsActions() {
        show(DownloadsUi(now = listOf(running), stopped = listOf(failed), doneToday = listOf(done)))
        compose.onNodeWithText("Now").assertIsDisplayed()
        compose.onNodeWithText("Stopped").assertIsDisplayed()
        compose.onNodeWithText("Done today").assertIsDisplayed()
        compose.onNodeWithText("500 B of 1.0 KB").assertIsDisplayed()
        compose.onNodeWithText("No connection.").assertIsDisplayed()
        compose.onNodeWithText("Retry").performClick()
        compose.onNodeWithText("Open").performClick()
        compose.onNodeWithText("Owls").performClick()
        assertThat(calls).containsExactly("retry:item:emma", "open:item:owls", "open:item:owls").inOrder()
    }

    @Test fun cancelStopsARowThatIsComing() {
        show(DownloadsUi(now = listOf(running)))
        compose.onNodeWithText("Cancel").performClick()
        assertThat(calls).containsExactly("cancel:item:wiki")
    }

    @Test fun aGroupOpensToItsFiles() {
        show(DownloadsUi(now = listOf(setup)))
        compose.onNodeWithText("1 of 2 · Queued").assertIsDisplayed()
        compose.onNodeWithText("Pack b").assertDoesNotExist()
        compose.onNodeWithContentDescription("Show files of Setting up").performClick()
        compose.onNodeWithText("Pack b").assertIsDisplayed()
        compose.onNodeWithText("Setting up").performClick()
        compose.onNodeWithText("Pack b").assertDoesNotExist()
    }

    @Test fun nothingDownloadingPointsToGetMore() {
        show(DownloadsUi())
        compose.onNodeWithText("Nothing downloading.").assertIsDisplayed()
        compose.onNodeWithText("Get more →").performClick()
        assertThat(calls).containsExactly("more")
    }

    @Test fun downloadsBelongsToTheLibrary() {
        lateinit var nav: NavHostController
        compose.setContent {
            nav = rememberNavController()
            NavHost(nav, startDestination = DownloadsRoute) { composable<DownloadsRoute> {} }
        }
        compose.runOnIdle { assertThat(railToolOf(nav.currentDestination!!)).isEqualTo(RailTool.LIBRARY) }
    }

    @Test fun theNotificationsLinkOpensDownloadsButARelaunchFromRecentsDoesNot() {
        val tapped = Intent(Intent.ACTION_MAIN).setData(Uri.parse(DOWNLOADS_LINK))
        assertThat(MainActivity.opensDownloads(tapped)).isTrue()
        assertThat(MainActivity.opensDownloads(Intent(tapped).addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY))).isFalse()
        assertThat(MainActivity.opensDownloads(Intent(Intent.ACTION_MAIN))).isFalse()
    }
}
