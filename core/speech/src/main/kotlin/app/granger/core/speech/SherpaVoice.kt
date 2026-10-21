package app.granger.core.speech

import java.io.File

/**
 * A voice directory as sherpa-onnx's `tts-models` release unpacks it. [id] is the directory's name
 * (`vits-piper-en_US-ljspeech-medium`, `kokoro-int8-multi-lang-v1_1`, …).
 */
sealed interface SherpaVoice {
    val dir: File
    val id: String get() = dir.name

    /** The ONNX model file. */
    val model: File

    /** The phonemizer's data directory, `espeak-ng-data/`. */
    val espeakData: File

    /** A Piper (VITS) voice: one `.onnx` model and `tokens.txt`. */
    data class Piper(override val dir: File, override val model: File, val tokens: File, override val espeakData: File) : SherpaVoice

    /**
     * A Kokoro voice: the model, its speaker table `voices.bin`, `tokens.txt`, and for the multi-language builds
     * one or more `lexicon-*.txt` files (joined for the engine) and a `dict/` directory.
     */
    data class Kokoro(
        override val dir: File,
        override val model: File,
        val voices: File,
        val tokens: File,
        override val espeakData: File,
        val lexicons: List<File>,
        val dictDir: File?,
    ) : SherpaVoice

    companion object {
        /** The voice in [dir], or null when it holds no recognisable voice. */
        fun detect(dir: File): SherpaVoice? {
            if (!dir.isDirectory) return null
            val files = dir.listFiles().orEmpty()
            val tokens = files.firstOrNull { it.name == "tokens.txt" } ?: return null
            val espeak = files.firstOrNull { it.isDirectory && it.name == "espeak-ng-data" } ?: return null
            val voices = files.firstOrNull { it.name == "voices.bin" }
            val models = files.filter { it.isFile && it.name.endsWith(".onnx") }
            if (voices != null) {
                // Prefer the int8 model when a directory holds both; it is the smaller one.
                val model = models.firstOrNull { "int8" in it.name } ?: models.firstOrNull() ?: return null
                return Kokoro(
                    dir = dir,
                    model = model,
                    voices = voices,
                    tokens = tokens,
                    espeakData = espeak,
                    lexicons = lexicons(files),
                    dictDir = files.firstOrNull { it.isDirectory && it.name == "dict" },
                )
            }
            val model = models.singleOrNull() ?: return null
            return Piper(dir, model, tokens, espeak)
        }

        /**
         * The lexicons a Kokoro build ships, one per language, US English preferred over British when both are
         * there (sherpa-onnx takes one English lexicon; the multi-language builds carry `lexicon-us-en.txt`,
         * `lexicon-gb-en.txt` and `lexicon-zh.txt`).
         */
        private fun lexicons(files: Array<out File>): List<File> {
            val all = files.filter { it.isFile && it.name.startsWith("lexicon") && it.name.endsWith(".txt") }.sortedBy { it.name }
            return if (all.any { it.name == "lexicon-us-en.txt" }) all.filterNot { it.name == "lexicon-gb-en.txt" } else all
        }

        /** Every voice directly under [root], by id. */
        fun list(root: File): List<SherpaVoice> =
            root.listFiles().orEmpty().filter { it.isDirectory }.sortedBy { it.name }.mapNotNull(::detect)
    }
}
