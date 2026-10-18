package app.granger.core.ai

import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.async
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ModelManagerTest {
    private val small = File("small.litertlm")
    private val large = File("large.litertlm")
    private val emb = File("emb.litertlm")
    private val all = InstalledModels(small, large, emb)

    private class Rig(
        val manager: ModelManager,
        val llms: FakeLlmFactory,
        val embedders: List<FakeEmbedder>,
        val installed: MutableStateFlow<InstalledModels>,
    )

    private fun TestScope.rig(
        tier: DeviceTier = DeviceTier.THINK_DEEPER,
        models: InstalledModels = all,
        useEmbedder: Boolean = true,
        failLoadFor: Map<String, Throwable> = emptyMap(),
    ): Rig {
        val llms = FakeLlmFactory(FakeClock(), failLoadFor)
        val embedders = mutableListOf<FakeEmbedder>()
        val installed = MutableStateFlow(models)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val manager = ModelManager(
            tier, installed, llms,
            { embedders += FakeEmbedder(); embedders.last() },
            backgroundScope, useEmbedder, io = dispatcher,
        )
        return Rig(manager, llms, embedders, installed)
    }

    @Test fun readinessFollowsTierAndFiles() = runTest {
        assertThat(rig(tier = DeviceTier.LIBRARY_ONLY).manager.readiness.value).isEqualTo(ResearchReadiness.UNSUPPORTED)
        val r = rig(tier = DeviceTier.ASK, models = InstalledModels())
        runCurrent()
        assertThat(r.manager.readiness.value).isEqualTo(ResearchReadiness.MODELS_MISSING)
        r.installed.value = InstalledModels(small = small)
        runCurrent()
        assertThat(r.manager.readiness.value).isEqualTo(ResearchReadiness.READY)
    }

    @Test fun thinkDeeperNeedsTierAndLargeFile() = runTest {
        val r = rig(models = InstalledModels(small = small))
        runCurrent()
        assertThat(r.manager.canThinkDeeper.value).isFalse()
        r.installed.value = all
        runCurrent()
        assertThat(r.manager.canThinkDeeper.value).isTrue()
        val ask = rig(tier = DeviceTier.ASK)
        runCurrent()
        assertThat(ask.manager.canThinkDeeper.value).isFalse()
    }

    @Test fun sameSizeIsReused() = runTest {
        val r = rig()
        val a = r.manager.engine(LlmSize.SMALL)
        val b = r.manager.engine(LlmSize.SMALL)
        assertThat(b).isSameInstanceAs(a)
        assertThat(r.llms.events.count { it.startsWith("create") }).isEqualTo(1)
    }

    @Test fun swappingClosesTheOldModelFirst() = runTest {
        val r = rig()
        r.manager.engine(LlmSize.SMALL)
        r.manager.engine(LlmSize.LARGE)
        val e = r.llms.events
        assertThat(e.indexOf("close ${small.path}")).isLessThan(e.indexOf("load ${large.path}"))
        assertThat(r.llms.maxOpen).isEqualTo(1)
    }

    @Test fun aMissingFileLeavesTheResidentModelLoaded() = runTest {
        val r = rig(models = InstalledModels(small = small, embedder = emb))
        val resident = r.manager.engine(LlmSize.SMALL)
        val ex = runCatching { r.manager.engine(LlmSize.LARGE) }.exceptionOrNull() as ModelLoadException
        assertThat(ex.size).isEqualTo(LlmSize.LARGE)
        assertThat(r.llms.openEngines).hasSize(1)
        assertThat(r.manager.engine(LlmSize.SMALL)).isSameInstanceAs(resident)
    }

    @Test fun loadRightAfterLaunchWaitsForTheModelList() = runTest {
        // At startup the list is empty until the catalog has loaded from its cache.
        val r = rig(models = InstalledModels())
        val engine = async { r.manager.engine(LlmSize.SMALL) }
        runCurrent()
        assertThat(engine.isCompleted).isFalse()
        r.installed.value = all
        assertThat(engine.await()).isNotNull()
        assertThat(r.llms.events).contains("load ${small.path}")
    }

    @Test fun largeIsRefusedBelowTheThinkDeeperTier() = runTest {
        val r = rig(tier = DeviceTier.ASK)
        val resident = r.manager.engine(LlmSize.SMALL)
        val ex = runCatching { r.manager.engine(LlmSize.LARGE) }.exceptionOrNull() as ModelLoadException
        assertThat(ex.size).isEqualTo(LlmSize.LARGE)
        assertThat(r.llms.events.none { it.contains(large.path) }).isTrue()
        assertThat(r.manager.engine(LlmSize.SMALL)).isSameInstanceAs(resident)
    }

    @Test fun largeClosesTheEmbedder() = runTest {
        val r = rig()
        r.manager.engine(LlmSize.SMALL)
        assertThat(r.manager.embedder()).isNotNull()
        r.manager.engine(LlmSize.LARGE)
        assertThat(r.embedders.single().closed).isTrue()
    }

    @Test fun embedderOnlyAlongsideSmall() = runTest {
        val r = rig()
        r.manager.engine(LlmSize.LARGE)
        assertThat(r.manager.embedder()).isNull()
        assertThat(r.embedders).isEmpty()
    }

    @Test fun loadFailureThrowsModelLoadExceptionAndLeavesNothingResident() = runTest {
        val boom = IllegalStateException("boom")
        val r = rig(failLoadFor = mapOf(large.path to boom))
        r.manager.engine(LlmSize.SMALL)
        val ex = runCatching { r.manager.engine(LlmSize.LARGE) }.exceptionOrNull() as ModelLoadException
        assertThat(ex.size).isEqualTo(LlmSize.LARGE)
        assertThat(ex.cause).hasMessageThat().isEqualTo("boom")
        assertThat(r.llms.openEngines).isEmpty()
    }

    @Test fun releaseSoonClosesAfterTheDelay() = runTest {
        val r = rig()
        r.manager.engine(LlmSize.SMALL)
        r.manager.embedder()
        r.manager.releaseSoon()
        advanceTimeBy(119_000)
        assertThat(r.llms.openEngines).isNotEmpty()
        advanceTimeBy(2_000)
        runCurrent()
        assertThat(r.llms.openEngines).isEmpty()
        assertThat(r.embedders.single().closed).isTrue()
    }

    @Test fun useBeforeTheDelayCancelsRelease() = runTest {
        val r = rig()
        r.manager.engine(LlmSize.SMALL)
        r.manager.releaseSoon()
        advanceTimeBy(60_000)
        r.manager.engine(LlmSize.SMALL)
        advanceTimeBy(200_000)
        runCurrent()
        assertThat(r.llms.openEngines).hasSize(1)
    }

    @Test fun embedderDisabledGivesNull() = runTest {
        val r = rig(useEmbedder = false)
        r.manager.engine(LlmSize.SMALL)
        assertThat(r.manager.embedder()).isNull()
        assertThat(r.embedders).isEmpty()
    }

    @Test fun cancelDuringLlmLoadKeepsTheEngineResidentNotLeaked() = runTest {
        var job: Job? = null
        val llms = object : LlmEngineFactory {
            val created = mutableListOf<ScriptedLlm>()
            override fun create(modelPath: String, backend: LlmBackend): LlmEngine =
                object : ScriptedLlm(FakeClock(), emptyList()) {
                    override fun load() { job?.cancel(); super.load() }
                }.also { created += it }
        }
        val m = ModelManager(DeviceTier.ASK, MutableStateFlow(all), llms, { FakeEmbedder() }, backgroundScope, true, io = StandardTestDispatcher(testScheduler))
        job = launch { m.engine(LlmSize.SMALL) }
        runCurrent()
        assertThat(job.isCancelled).isTrue()
        // Nothing orphaned: the loaded engine is the resident one, and the next call reuses it.
        val again = m.engine(LlmSize.SMALL)
        assertThat(llms.created).hasSize(1)
        assertThat(again).isSameInstanceAs(llms.created.single())
    }

    @Test fun cancelDuringEmbedderLoadKeepsItResident() = runTest {
        var job: Job? = null
        val created = mutableListOf<FakeEmbedder>()
        val factory = TextEmbedderFactory {
            object : FakeEmbedder() { override fun load() { job?.cancel() } }.also { created += it }
        }
        val m = ModelManager(DeviceTier.ASK, MutableStateFlow(all), FakeLlmFactory(FakeClock()), factory, backgroundScope, true, io = StandardTestDispatcher(testScheduler))
        job = launch { m.embedder() }
        runCurrent()
        assertThat(job.isCancelled).isTrue()
        assertThat(m.embedder()).isSameInstanceAs(created.single())
        assertThat(created).hasSize(1)
    }

    @Test fun releaseNowThenEngineKeepsTheEngine() = runTest {
        val r = rig()
        r.manager.engine(LlmSize.SMALL)
        r.manager.releaseNow()
        val e = r.manager.engine(LlmSize.SMALL)
        runCurrent()
        assertThat(r.llms.openEngines).containsExactly(e)
    }

    @Test fun releaseNowWhileHeldWaitsForLetGo() = runTest {
        val r = rig()
        r.manager.engine(LlmSize.SMALL)
        val letGo = r.manager.hold()
        r.manager.releaseNow()
        runCurrent()
        assertThat(r.llms.openEngines).hasSize(1)
        letGo()
        runCurrent()
        assertThat(r.llms.openEngines).isEmpty()
    }

    @Test fun releaseNowWhileHeldSurvivesALaterUse() = runTest {
        val r = rig()
        r.manager.engine(LlmSize.SMALL)
        val turn = r.manager.hold()
        r.manager.releaseNow() // the app went to the background mid-turn
        val answer = r.manager.hold()
        r.manager.engine(LlmSize.SMALL) // the same turn's next stage
        answer()
        runCurrent()
        assertThat(r.llms.openEngines).hasSize(1)
        turn()
        runCurrent()
        assertThat(r.llms.openEngines).isEmpty()
    }

    @Test fun releaseRequestedMidTurnIsDroppedWhenAppReturns() = runTest {
        val r = rig()
        r.manager.engine(LlmSize.SMALL)
        val turn = r.manager.hold()
        r.manager.releaseNow() // the app went to the background mid-turn...
        r.manager.appReturned() // ...and came back before the turn ended
        turn()
        runCurrent()
        assertThat(r.llms.openEngines).hasSize(1) // not closed at the turn's end...
        advanceTimeBy(121_000)
        runCurrent()
        assertThat(r.llms.openEngines).isEmpty() // ...but a quiet spell later it still goes
    }

    @Test fun appReturningKeepsTheQuietSpellRelease() = runTest {
        val r = rig()
        r.manager.engine(LlmSize.SMALL)
        val turn = r.manager.hold()
        r.manager.releaseSoon() // the turn's end asks for a quiet-spell release
        r.manager.appReturned()
        turn()
        advanceTimeBy(121_000)
        runCurrent()
        assertThat(r.llms.openEngines).isEmpty()
    }

    @Test fun releaseSoonTimerWhileHeldWaitsForLetGo() = runTest {
        val r = rig()
        r.manager.engine(LlmSize.SMALL)
        val letGo = r.manager.hold()
        r.manager.releaseSoon()
        advanceTimeBy(150_000)
        runCurrent()
        assertThat(r.llms.openEngines).hasSize(1)
        letGo()
        advanceTimeBy(119_000)
        assertThat(r.llms.openEngines).hasSize(1)
        advanceTimeBy(2_000)
        runCurrent()
        assertThat(r.llms.openEngines).isEmpty()
    }

    @Test fun letGoTwiceCountsOnce() = runTest {
        val r = rig()
        r.manager.engine(LlmSize.SMALL)
        val a = r.manager.hold()
        val b = r.manager.hold()
        r.manager.releaseNow()
        a(); a(); a()
        runCurrent()
        assertThat(r.llms.openEngines).hasSize(1) // b still holds
        b()
        runCurrent()
        assertThat(r.llms.openEngines).isEmpty()
    }
}
