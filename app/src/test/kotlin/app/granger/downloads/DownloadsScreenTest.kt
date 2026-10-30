package app.granger.downloads

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.granger.DownloadsRoute
import app.granger.MainActivity
import app.granger.core.downloads.DOWNLOADS_LINK
import app.granger.core.downloads.WaitReason
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

    private val header = DownloadsHeader(
        paused = false, files = 14, filesDone = 3, bytes = 9_800_000_000, bytesDone = 1_200_000_000, waiting = 10,
        bytesPerSecond = 2_100_000, timeLeftMillis = 25 * 60_000L, bytesToGo = 8_600_000_000, freeBytes = 41_000_000_000, volumeLabel = "Phone",
    )
    private val running = DownloadRow(
        "item:wiki", "Wikipedia Top", RowState.RUNNING, 312_000_000, 980_000_000, groupId = "onboarding:1", groupLabel = "Setting up",
        itemIds = listOf("wiki"),
    )
    private fun waiting(id: String, place: Int) =
        DownloadRow("item:$id", "Pack $id", RowState.QUEUED, 0, 1_000_000, itemIds = listOf(id), reason = WaitReason.InLine(place))
    private val failed = DownloadRow("item:emma", "Emma", RowState.FAILED, 0, 1_000, itemIds = listOf("emma"), error = "No connection.")
    private val failed2 = failed.copy(key = "item:owl", title = "Owl", itemIds = listOf("owl"), error = "Timed out.")
    private val gone = DownloadRow("item:wikem", "WikEM", RowState.FAILED, 0, 1_000, itemIds = listOf("wikem"), error = "Kiwix no longer offers this file", gone = true)
    private val done = DownloadRow("item:owls", "Owls", RowState.DONE, 1_000, 1_000, itemIds = listOf("owls"), open = OpenTarget.Pack("owls"))

    private fun show(ui: DownloadsUi) = compose.setContent {
        DownloadsContent(
            ui, onBack = {},
            DownloadsActions(
                onPause = { calls += "pause" },
                onResume = { calls += "resume" },
                onDownloadNext = { calls += "next:${it.key}" },
                onCancel = { calls += "cancel:${it.key}" },
                onCancelGroup = { calls += "cancelGroup:${it.groupId}" },
                onRetry = { calls += "retry:${it.key}" },
                onRetryAll = { rows -> calls += "retryAll:${rows.map { it.key }}" },
                onRemove = { calls += "remove:${it.key}" },
                onOpen = { calls += "open:${it.key}" },
                onGetMore = { calls += "more" },
            ),
            now = 0,
        )
    }

    @Test fun theHeaderShowsTheRoundAndPausesAll() {
        show(DownloadsUi(header = header, now = running))
        compose.onNodeWithText("3 of 14 · 1.2 GB of 9.8 GB · about 25 min left").assertIsDisplayed()
        compose.onNodeWithText("8.6 GB to go · 41 GB free on Phone").assertIsDisplayed()
        compose.onNodeWithText("2.1 MB/s").assertIsDisplayed()
        compose.onNodeWithText("312 MB of 980 MB · 2.1 MB/s").assertIsDisplayed()
        compose.onNodeWithText("Setting up").assertIsDisplayed()
        compose.onNodeWithText("Pause all").performClick()
        assertThat(calls).containsExactly("pause")
    }

    @Test fun pausedItOffersResume() {
        show(DownloadsUi(header = header.copy(paused = true), upNext = listOf(waiting("a", 1).copy(reason = WaitReason.Paused))))
        compose.onNodeWithText("Paused · 10 waiting").assertIsDisplayed()
        compose.onNodeWithText("1.0 MB · Paused").assertIsDisplayed()
        compose.onNodeWithText("Resume").performClick()
        assertThat(calls).containsExactly("resume")
    }

    @Test fun aWaitingRowsMenuHasDownloadNextAndCancel() {
        show(DownloadsUi(header = header, now = running, upNext = listOf(waiting("a", 1), waiting("b", 2))))
        compose.onNodeWithText("1.0 MB · 2nd in line").assertIsDisplayed()
        compose.onNodeWithContentDescription("More for Pack b").performClick()
        compose.onNodeWithText("Download next").performClick()
        compose.onNodeWithContentDescription("More for Wikipedia Top").performClick()
        compose.onNodeWithText("Download next").assertDoesNotExist()
        compose.onNodeWithText("Cancel all of Setting up").performClick()
        assertThat(calls).containsExactly("next:item:b", "cancelGroup:onboarding:1").inOrder()
    }

    @Test fun stoppedRowsRetryOneOrAllAndAGoneFileOffersRemove() {
        show(DownloadsUi(stopped = listOf(failed, failed2, gone)))
        compose.onNodeWithText("No connection.").assertIsDisplayed()
        compose.onNodeWithText("Kiwix no longer offers this file").assertIsDisplayed()
        compose.onNodeWithText("Remove").performClick()
        compose.onNodeWithText("Retry all").performClick()
        assertThat(calls).containsExactly("remove:item:wikem", "retryAll:[item:emma, item:owl]").inOrder()
    }

    @Test fun aLongQueueShowsTwentyThenShowAll() {
        show(DownloadsUi(header = header, upNext = (1..25).map { waiting("p$it", it) }))
        compose.onNodeWithTag("downloads-list").performScrollToNode(hasText("Show all 25"))
        compose.onNodeWithText("Pack p21").assertDoesNotExist()
        compose.onNodeWithText("Show all 25").performClick()
        compose.onNodeWithTag("downloads-list").performScrollToNode(hasText("Pack p25"))
        compose.onNodeWithText("Pack p25").assertIsDisplayed()
    }

    @Test fun doneTodayOpens() {
        show(DownloadsUi(doneToday = listOf(done)))
        compose.onNodeWithText("Done today").assertIsDisplayed()
        compose.onNodeWithText("Open").performClick()
        compose.onNodeWithText("Owls").performClick()
        assertThat(calls).containsExactly("open:item:owls", "open:item:owls").inOrder()
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
