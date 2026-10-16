package app.granger.core.ai.spike

import app.granger.core.ai.LlmBackend

data class SpikeModels(
    val small: String,
    val large: String,
    val embedder: String,
) {
    companion object {
        const val SMALL_CPU = "gemma-4-E2B-it.litertlm"
        const val SMALL_GPU = "gemma-4-E2B-it-gpu.litertlm"
        const val LARGE_CPU = "gemma-4-E4B-it.litertlm"
        const val LARGE_GPU = "gemma-4-E4B-it-gpu.litertlm"
        const val EMBEDDER = "embeddinggemma-2-text-270m.litertlm"

        fun forBackend(backend: LlmBackend, dir: String): SpikeModels = SpikeModels(
            small = "$dir/" + if (backend == LlmBackend.GPU) SMALL_GPU else SMALL_CPU,
            large = "$dir/" + if (backend == LlmBackend.GPU) LARGE_GPU else LARGE_CPU,
            embedder = "$dir/$EMBEDDER",
        )
    }
}
