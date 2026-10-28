package app.granger.settings

import app.granger.FakeAppearancePrefs
import app.granger.FakeDownloads
import app.granger.FakePrefs
import app.granger.FakeVolumes
import app.granger.MainDispatcherRule
import app.granger.archive.ArchivePrefs
import app.granger.volume
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class SettingsViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val downloads = FakeDownloads()
    private val volumes = FakeVolumes(listOf(volume("/internal", "Internal storage"), volume("/sd", "SD card")))
    private val prefs = FakePrefs()
    private val archivePrefs = FakeArchivePrefs()
    private val appearancePrefs = FakeAppearancePrefs()

    private class FakeArchivePrefs : ArchivePrefs {
        private val state = MutableStateFlow(false)
        override val waiverAccepted: StateFlow<Boolean> = state
        override fun accept() { state.value = true }
        override fun withdraw() { state.value = false }
    }

    private fun TestScope.viewModel(): SettingsViewModel {
        val vm = SettingsViewModel(downloads, volumes, prefs, archivePrefs, appearancePrefs, appScope = this, io = main.dispatcher)
        backgroundScope.launch { vm.ui.collect {} }
        testScheduler.advanceUntilIdle()
        return vm
    }

    @Test fun openingSettingsListsTheVoicesForTheVoiceRow() = runTest(main.dispatcher) {
        var refreshes = 0
        SettingsViewModel(downloads, volumes, prefs, archivePrefs, appearancePrefs, appScope = this, io = main.dispatcher, refreshVoices = { refreshes++ })
        testScheduler.advanceUntilIdle()
        assertThat(refreshes).isEqualTo(1)
    }

    @Test fun choicesPersistAndReachDownloads() = runTest(main.dispatcher) {
        val vm = viewModel()
        assertThat(vm.ui.value.selectedVolume).isEqualTo("/internal")
        vm.selectVolume("/sd")
        vm.setWifiOnly(false)
        testScheduler.advanceUntilIdle()
        assertThat(prefs.volumePath).isEqualTo("/sd")
        assertThat(prefs.wifiOnly).isFalse()
        assertThat(downloads.calls).contains("wifi:false")
        assertThat(vm.ui.value.selectedVolume).isEqualTo("/sd")
        assertThat(vm.ui.value.wifiOnly).isFalse()
    }

    @Test fun archiveWaiverCanBeAcceptedAndWithdrawn() = runTest(main.dispatcher) {
        val vm = viewModel()
        assertThat(vm.ui.value.archiveEverything).isFalse()
        vm.acceptArchiveWaiver()
        testScheduler.advanceUntilIdle()
        assertThat(vm.ui.value.archiveEverything).isTrue()
        assertThat(archivePrefs.waiverAccepted.value).isTrue()
        vm.withdrawArchiveWaiver()
        testScheduler.advanceUntilIdle()
        assertThat(vm.ui.value.archiveEverything).isFalse()
        assertThat(archivePrefs.waiverAccepted.value).isFalse()
    }

    @Test fun railSideIsShownAndChangedAtOnce() = runTest(main.dispatcher) {
        val vm = viewModel()
        assertThat(vm.railSide.value).isEqualTo(RailSide.LEFT)
        vm.setRailSide(RailSide.RIGHT)
        assertThat(vm.railSide.value).isEqualTo(RailSide.RIGHT)
        assertThat(appearancePrefs.railSide.value).isEqualTo(RailSide.RIGHT)
    }
}
