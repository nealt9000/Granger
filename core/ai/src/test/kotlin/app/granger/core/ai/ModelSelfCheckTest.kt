package app.granger.core.ai

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ModelSelfCheckTest {
    private val dir = Files.createTempDirectory("check").toFile()
    private val model = File(dir, "gemma.litertlm").apply { writeText("model bytes") }
    private val store = File(dir, "model-check.txt")

    private class Llm(val reply: () -> Flow<String>, val failLoad: Boolean = false) : LlmEngine {
        var closed = false
        val prompts = mutableListOf<String>()
        override fun load() { if (failLoad) error("no GPU") }
        override fun generate(prompt: String, maxTokens: Int): Flow<String> { prompts += prompt; return reply() }
        override fun close() { closed = true }
    }

    private fun TestScope.manager(llm: Llm, checks: ModelCheckStore = ModelCheckStore(store)) = ModelManager(
        DeviceTier.ASK, MutableStateFlow(InstalledModels(model)), { _, _ -> llm }, { FakeEmbedder() },
        backgroundScope, { false }, io = StandardTestDispatcher(testScheduler), checks = checks,
    )

    @Test fun aRightReplyPassesOnceAndIsNotAskedAgain() = runTest {
        val llm = Llm({ flowOf("4") })
        val m = manager(llm)
        assertThat(m.engine()).isSameInstanceAs(llm)
        assertThat(llm.prompts).containsExactly(SelfCheck.PROMPT)
        assertThat(store.readText()).isEqualTo("${ModelCheckStore.keyOf(model)}=passed\n")
        // A new process: passed stays passed, no second check.
        val again = Llm({ flowOf("4") })
        manager(again).engine()
        assertThat(again.prompts).isEmpty()
        runCurrent()
        assertThat(m.readiness.value).isEqualTo(ResearchReadiness.READY)
    }

    @Test fun aWrongReplyBarsTheModelAndSaysWhy() = runTest {
        val llm = Llm({ flowOf("<pad><pad>") })
        val m = manager(llm)
        val ex = runCatching { m.engine() }.exceptionOrNull() as ModelLoadException
        assertThat((ex.cause as ModelCheckFailedException).why).isEqualTo(CheckFailure.WRONG_REPLY)
        assertThat(llm.closed).isTrue()
        runCurrent()
        assertThat(m.checkFailure.value).isEqualTo(CheckFailure.WRONG_REPLY)
        assertThat(m.readiness.value).isEqualTo(ResearchReadiness.FAILED_CHECK)
        // Barred: the next question doesn't load it again.
        val next = Llm({ flowOf("4") })
        assertThat(runCatching { manager(next).engine() }.isFailure).isTrue()
        assertThat(next.prompts).isEmpty()
    }

    @Test fun aLoadErrorFailsTheCheck() = runTest {
        val m = manager(Llm({ flowOf("4") }, failLoad = true))
        assertThat(runCatching { m.engine() }.isFailure).isTrue()
        runCurrent()
        assertThat(m.checkFailure.value).isEqualTo(CheckFailure.LOAD_FAILED)
    }

    @Test fun aReplyThatNeverComesIsTooSlow() = runTest {
        val m = manager(Llm({ flow { awaitCancellation() } }))
        assertThat(runCatching { m.engine() }.isFailure).isTrue()
        runCurrent()
        assertThat(m.checkFailure.value).isEqualTo(CheckFailure.TOO_SLOW)
    }

    @Test fun aCheckStillRunningAtLaunchIsACrash() = runTest {
        // The process died mid-check (a native crash in load or generate).
        store.writeText("${ModelCheckStore.keyOf(model)}=running\n")
        val m = manager(Llm({ flowOf("4") }))
        runCurrent()
        assertThat(m.checkFailure.value).isEqualTo(CheckFailure.CRASHED)
        assertThat(m.readiness.value).isEqualTo(ResearchReadiness.FAILED_CHECK)
        assertThat(store.readText()).contains("failed:CRASHED")
    }

    @Test fun tryAgainChecksAfresh() = runTest {
        store.writeText("${ModelCheckStore.keyOf(model)}=failed:WRONG_REPLY\n")
        val llm = Llm({ flowOf("The answer is 4.") })
        val m = manager(llm)
        m.retryCheck()
        runCurrent()
        assertThat(m.readiness.value).isEqualTo(ResearchReadiness.READY)
        m.engine()
        assertThat(llm.prompts).containsExactly(SelfCheck.PROMPT)
    }

    @Test fun aNewDownloadOfTheSameNameIsCheckedAgain() = runTest {
        store.writeText("${ModelCheckStore.keyOf(model)}=failed:WRONG_REPLY\n")
        model.writeText("a different, longer download")
        val llm = Llm({ flowOf("4") })
        manager(llm).engine()
        assertThat(llm.prompts).containsExactly(SelfCheck.PROMPT)
    }

    @Test fun withNoStoreNothingIsChecked() = runTest {
        val llm = Llm({ flowOf("nonsense") })
        manager(llm, ModelCheckStore(null)).engine()
        assertThat(llm.prompts).isEmpty()
    }

    @Test fun passesJudgesTheSum() {
        assertThat(SelfCheck.passes("4")).isTrue()
        assertThat(SelfCheck.passes("2 + 2 = 4.")).isTrue()
        assertThat(SelfCheck.passes("Four")).isTrue()
        assertThat(SelfCheck.passes("44")).isFalse()
        assertThat(SelfCheck.passes("")).isFalse()
    }
}
