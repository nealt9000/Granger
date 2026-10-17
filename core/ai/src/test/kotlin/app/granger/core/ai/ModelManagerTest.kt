package app.granger.core.ai

import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
}
