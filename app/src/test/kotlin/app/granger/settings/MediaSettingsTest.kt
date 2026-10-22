package app.granger.settings

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.DocumentsContract
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import app.granger.FakeAppearancePrefs
import app.granger.FakeDownloads
import app.granger.FakePrefs
import app.granger.FakeVolumes
import app.granger.archive.ArchivePrefs
import app.granger.core.library.MediaFolder
import app.granger.volume
import app.granger.core.player.PlayerSettingsKeys
import app.granger.core.player.ReplayGainMode
import app.granger.media.MediaDocumentsProvider
import app.granger.media.MediaPermissionStatus
import app.granger.media.MediaSharing
import app.granger.media.PermissionWatch
import app.granger.media.audioPermission
import app.granger.media.mediaPermissionStatus
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MediaSettingsTest {

    private val prefs = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences(PlayerSettingsKeys.PREFS, Context.MODE_PRIVATE)

    private class FakeFolders : MediaFolders {
        val list = MutableStateFlow<List<MediaFolder>>(emptyList())
        override val all: Flow<List<MediaFolder>> = list
        override suspend fun add(folder: MediaFolder) { list.update { all -> if (all.any { it.uri == folder.uri }) all else all + folder } }
        override suspend fun remove(uri: String) { list.update { all -> all.filterNot { it.uri == uri } } }
    }

    private class FakeGrants : FolderGrants {
        val taken = mutableListOf<String>()
        val released = mutableListOf<String>()
        override fun take(uri: String) { taken += uri }
        override fun release(uri: String) { released += uri }
    }

    private val folders = FakeFolders()
    private val grants = FakeGrants()
    private var scans = 0
    private var scanFails = false
    private var scanError: Throwable? = null

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val provider = ComponentName(context, MediaDocumentsProvider::class.java)

    private fun TestScope.settings(sharing: OtherAppsSharing = ComponentOtherAppsSharing(context)): MediaSettings {
        val s = MediaSettings(folders, grants, prefs, sharing, scanner = {
            scans++
            scanError?.let { throw it }
            if (scanFails) error("no")
            ScanSummary(songs = 12, audiobooks = 2)
        }, scope = backgroundScope, work = this, now = { 77L })
        backgroundScope.launch { s.ui.collect {} }
        settle()
        return s
    }

    /** Foreground work (folder changes, scans) to the end, then the background UI state catches up. */
    private fun TestScope.settle() {
        testScheduler.advanceUntilIdle()
        testScheduler.runCurrent()
    }

    private val musicTree = "content://com.android.externalstorage.documents/tree/primary%3AMusic%2FRock"

    @Test fun addingAFolderTakesAReadGrantStoresItAndRescans() = runTest {
        val s = settings()
        s.addFolder(musicTree, audiobooks = false)
        settle()
        assertThat(grants.taken).containsExactly(musicTree)
        assertThat(folders.list.value).containsExactly(MediaFolder(musicTree, audiobooks = false, addedAt = 77L))
        assertThat(scans).isEqualTo(1)
        assertThat(s.ui.value.folders.single().name).isEqualTo("Music/Rock")
        assertThat(s.ui.value.scan).isEqualTo(RescanState.Done(ScanSummary(12, 2)))
    }

    /** A scan that dies with an Error (out of memory, a native-library link error) still ends as Failed, not Running. */
    @Test fun rescanEndingInAnErrorShowsFailedNotRunning() = runTest {
        scanError = StackOverflowError("deep")
        val s = settings()
        s.rescan()
        settle()
        assertThat(s.ui.value.scan).isEqualTo(RescanState.Failed)

        // And the button works again.
        scanError = null
        s.rescan()
        settle()
        assertThat(s.ui.value.scan).isEqualTo(RescanState.Done(ScanSummary(12, 2)))
    }

    @Test fun removingAFolderReleasesItsGrantAndRescans() = runTest {
        folders.list.value = listOf(MediaFolder(musicTree, audiobooks = false, 1L), MediaFolder("content://x/tree/books", audiobooks = true, 2L))
        val s = settings()
        assertThat(s.ui.value.folders).hasSize(2)
        s.removeFolder(musicTree)
        settle()
        assertThat(folders.list.value.map { it.uri }).containsExactly("content://x/tree/books")
        assertThat(grants.released).containsExactly(musicTree)
        assertThat(grants.taken).isEmpty()
        assertThat(scans).isEqualTo(1)
        assertThat(s.ui.value.folders.map { it.uri }).containsExactly("content://x/tree/books")
        assertThat(s.ui.value.folders.single().audiobooks).isTrue()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun deniedPermissionShowsGrantButtonAndNoCrash() {
        // The model: a scan without the permission fails (or finds nothing); Settings shows that and keeps working.
        scanFails = true
        var loaded: MediaSettingsUi? = null
        runTest {
            val s = settings()
            s.setPermission(MediaPermissionStatus.DENIED)
            s.rescan()
            settle()
            loaded = s.ui.value
        }
        val denied = loaded!!
        assertThat(denied.permission).isEqualTo(MediaPermissionStatus.DENIED)
        assertThat(denied.scan).isEqualTo(RescanState.Failed)

        // The section: "Allow access" while it can be asked, "Open app settings" once permanently denied.
        runComposeUiTest {
            var requested = 0
            var openedSettings = 0
            var ui by mutableStateOf(denied)
            setContent {
                LazyColumn {
                    mediaSettingsSection(
                        ui, onRequestPermission = { requested++ }, onOpenAppSettings = { openedSettings++ },
                        onAddFolder = {}, onRemoveFolder = {}, onRescan = {}, onShowInOtherApps = {}, onReplayGain = {}, onSkipBack = {},
                        onSkipForward = {}, onHeadsetAutoplay = {},
                    )
                }
            }
            onNodeWithText("Allow access").assertIsDisplayed().performClick()
            assertThat(requested).isEqualTo(1)

            ui = denied.copy(permission = MediaPermissionStatus.PERMANENTLY_DENIED)
            onNodeWithText("Open app settings").assertIsDisplayed().performClick()
            assertThat(openedSettings).isEqualTo(1)

            ui = denied.copy(permission = MediaPermissionStatus.GRANTED)
            onNodeWithText("Allowed").assertIsDisplayed()
            assertThat(onAllNodes(hasText("Allow access")).fetchSemanticsNodes()).isEmpty()
        }
    }

    @Test fun permissionStatusFollowsGrantAndRationale() {
        assertThat(audioPermission(33)).isEqualTo(Manifest.permission.READ_MEDIA_AUDIO)
        assertThat(audioPermission(32)).isEqualTo(Manifest.permission.READ_EXTERNAL_STORAGE)
        assertThat(audioPermission(29)).isEqualTo(Manifest.permission.READ_EXTERNAL_STORAGE)
        assertThat(mediaPermissionStatus(granted = true, askedBefore = true, showRationale = false)).isEqualTo(MediaPermissionStatus.GRANTED)
        // Never asked: rationale is false too, but asking is still possible.
        assertThat(mediaPermissionStatus(granted = false, askedBefore = false, showRationale = false)).isEqualTo(MediaPermissionStatus.DENIED)
        assertThat(mediaPermissionStatus(granted = false, askedBefore = true, showRationale = true)).isEqualTo(MediaPermissionStatus.DENIED)
        assertThat(mediaPermissionStatus(granted = false, askedBefore = true, showRationale = false))
            .isEqualTo(MediaPermissionStatus.PERMANENTLY_DENIED)
    }

    @Test fun permissionChangeOnResumeRescansOnlyWhenItChanged() {
        var granted = false
        var changes = 0
        val watch = PermissionWatch({ granted }) { changes++ }
        watch.check()
        watch.check()
        assertThat(changes).isEqualTo(0)
        granted = true
        watch.check()
        watch.check()
        assertThat(changes).isEqualTo(1)
        granted = false
        watch.check()
        assertThat(changes).isEqualTo(2)
    }

    @Test fun playerSettingsWriteThePlayerPrefsWithDefaults() = runTest {
        val s = settings()
        assertThat(s.ui.value.replayGain).isEqualTo(ReplayGainMode.AUTO)
        assertThat(s.ui.value.skipBackS).isEqualTo(30)
        assertThat(s.ui.value.skipForwardS).isEqualTo(30)
        assertThat(s.ui.value.headsetAutoplay).isFalse()
        s.setReplayGain(ReplayGainMode.ALBUM)
        s.setSkipBack(10)
        s.setSkipForward(60)
        s.setHeadsetAutoplay(true)
        settle()
        assertThat(prefs.getString(PlayerSettingsKeys.REPLAYGAIN_MODE, null)).isEqualTo("ALBUM")
        assertThat(prefs.getInt(PlayerSettingsKeys.SKIP_BACK_S, 0)).isEqualTo(10)
        assertThat(prefs.getInt(PlayerSettingsKeys.SKIP_FORWARD_S, 0)).isEqualTo(60)
        assertThat(prefs.getBoolean(PlayerSettingsKeys.HEADSET_AUTOPLAY, false)).isTrue()
        val ui = s.ui.value
        assertThat(listOf(ui.replayGain, ui.skipBackS, ui.skipForwardS, ui.headsetAutoplay))
            .containsExactly(ReplayGainMode.ALBUM, 10, 60, true).inOrder()
    }

    @Test fun showInOtherAppsIsOffByDefault() = runTest {
        // The manifest disables the provider, so the component's state is DEFAULT, which reads as off.
        assertThat(context.packageManager.getComponentEnabledSetting(provider))
            .isEqualTo(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)
        assertThat(MediaSharing.isEnabled(context)).isFalse()
        val info = context.packageManager.getProviderInfo(provider, PackageManager.MATCH_DISABLED_COMPONENTS)
        assertThat(info.enabled).isFalse()
        assertThat(info.authority).isEqualTo(MediaSharing.AUTHORITY)
        assertThat(info.exported).isTrue()
        assertThat(info.readPermission).isEqualTo(Manifest.permission.MANAGE_DOCUMENTS)
        assertThat(settings().ui.value.showInOtherApps).isFalse()
    }

    @Test fun switchOffDisablesTheProvider() = runTest {
        val resolver = shadowOf(context.contentResolver)
        val s = settings()
        s.setShowInOtherApps(true)
        settle()
        assertThat(context.packageManager.getComponentEnabledSetting(provider))
            .isEqualTo(PackageManager.COMPONENT_ENABLED_STATE_ENABLED)
        assertThat(MediaSharing.isEnabled(context)).isTrue()
        assertThat(s.ui.value.showInOtherApps).isTrue()
        val roots = DocumentsContract.buildRootsUri(MediaSharing.AUTHORITY)
        assertThat(resolver.notifiedUris.map { it.uri }).contains(roots)
        assertThat(context.packageManager.queryIntentContentProviders(Intent(DocumentsContract.PROVIDER_INTERFACE), 0)
            .map { it.providerInfo.authority }).contains(MediaSharing.AUTHORITY)

        resolver.notifiedUris.clear()
        s.setShowInOtherApps(false)
        settle()
        assertThat(context.packageManager.getComponentEnabledSetting(provider))
            .isEqualTo(PackageManager.COMPONENT_ENABLED_STATE_DISABLED)
        assertThat(MediaSharing.isEnabled(context)).isFalse()
        assertThat(s.ui.value.showInOtherApps).isFalse()
        assertThat(resolver.notifiedUris.map { it.uri }).contains(roots)
        // Off, the system picker can't find it.
        assertThat(context.packageManager.queryIntentContentProviders(Intent(DocumentsContract.PROVIDER_INTERFACE), 0)
            .map { it.providerInfo.authority }).doesNotContain(MediaSharing.AUTHORITY)
        // And a new Settings screen reads the component's state.
        assertThat(settings().ui.value.showInOtherApps).isFalse()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun showInOtherAppsRowTogglesTheSwitch() = runComposeUiTest {
        val toggled = mutableListOf<Boolean>()
        setContent {
            LazyColumn {
                mediaSettingsSection(
                    MediaSettingsUi(), onRequestPermission = {}, onOpenAppSettings = {}, onAddFolder = {}, onRemoveFolder = {},
                    onRescan = {}, onShowInOtherApps = { toggled += it }, onReplayGain = {}, onSkipBack = {}, onSkipForward = {},
                    onHeadsetAutoplay = {},
                )
            }
        }
        onNodeWithText("Show in other apps").assertIsDisplayed()
        onNodeWithText(
            "Granger's downloaded music and audiobooks appear in the Files app and in other apps' file pickers, read-only. " +
                "Music players that only read the phone's media list won't list them.",
        ).assertIsDisplayed()
        onNodeWithTag(SHOW_IN_OTHER_APPS_TAG).assertIsOff().performClick()
        assertThat(toggled).containsExactly(true)
    }

    @Test fun mediaSectionIndexComesFromTheKeys() {
        assertThat(settingsIndexOf(MEDIA_SECTION_KEY)).isEqualTo(7)
        assertThat(settingsIndexOf("appearance")).isEqualTo(3)
        assertThat(settingsIndexOf("models")).isEqualTo(0)
    }

    private class MemorySharing : OtherAppsSharing {
        var on = false
        override fun isEnabled() = on
        override fun setEnabled(on: Boolean) { this.on = on }
    }

    private class NoWaiver : ArchivePrefs {
        override val waiverAccepted: StateFlow<Boolean> = MutableStateFlow(false)
        override fun accept() {}
        override fun withdraw() {}
    }

    /** SettingsRoute(media = true): the screen opens at Music & audiobooks, found by its key. */
    @OptIn(ExperimentalTestApi::class)
    private fun openSettings(scrollToMedia: Boolean, check: androidx.compose.ui.test.ComposeUiTest.() -> Unit) = runComposeUiTest {
        val vm = SettingsViewModel(
            FakeDownloads(), FakeVolumes(listOf(volume("/internal", "Internal storage"))), FakePrefs(), NoWaiver(), FakeAppearancePrefs(),
            appScope = CoroutineScope(Dispatchers.Unconfined),
            mediaSettings = { scope ->
                MediaSettings(folders, grants, prefs, MemorySharing(), scanner = { ScanSummary(0, 0) }, scope = scope)
            },
        )
        setContent { SettingsScreen(vm, onModels = {}, onReadiness = {}, onBack = {}, scrollToMedia = scrollToMedia) }
        waitForIdle()
        check()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun settingsOpensAtTheMediaSectionByKey() = openSettings(scrollToMedia = true) {
        onNodeWithText("Music & audiobooks").assertIsDisplayed()
        onNodeWithText("Find music and audiobooks on this phone").assertIsDisplayed()
        // Scrolled past the rows above it.
        onNodeWithText("Models").assertDoesNotExist()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun settingsOpensAtTheTopWithoutTheMediaFlag() = openSettings(scrollToMedia = false) {
        onNodeWithText("Models").assertIsDisplayed()
    }
}
