package app.granger.core.ai.spike

import app.granger.core.ai.LlmBackend
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SpikeModelsTest {
    @Test fun cpuUsesGenericFiles() {
        val m = SpikeModels.forBackend(LlmBackend.CPU, "/m")
        assertThat(m.small).isEqualTo("/m/gemma-4-E2B-it.litertlm")
        assertThat(m.large).isEqualTo("/m/gemma-4-E4B-it.litertlm")
    }

    @Test fun gpuUsesGpuFiles() {
        val m = SpikeModels.forBackend(LlmBackend.GPU, "/m")
        assertThat(m.small).isEqualTo("/m/gemma-4-E2B-it-gpu.litertlm")
        assertThat(m.large).isEqualTo("/m/gemma-4-E4B-it-gpu.litertlm")
    }

    @Test fun embedderFilesAreShared() {
        val m = SpikeModels.forBackend(LlmBackend.GPU, "/m")
        assertThat(m.embedder).isEqualTo("/m/embeddinggemma-2-text-270m.litertlm")
    }
}
