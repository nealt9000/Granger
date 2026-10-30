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

    /** A VITS voice's sampling: more [noiseScale] is livelier and adds artifacts, more [noiseScaleW] varies timing. */
    data class Inference(val noiseScale: Float = 0.667f, val noiseScaleW: Float = 0.8f, val lengthScale: Float = 1f)

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

        /**
         * The speakers' names by speaker id, from the Piper config beside the model (`<model>.onnx.json`'s
         * `speaker_id_map`), or empty when the voice has one speaker or its speakers are only reader numbers (a
         * LibriTTS voice as trained): those are listed once, as speaker 0.
         */
        fun speakerNames(voice: SherpaVoice): List<String> {
            if (voice !is Piper) return emptyList()
            val config = File(voice.model.path + ".json").takeIf { it.isFile } ?: return emptyList()
            val map = Regex("\"speaker_id_map\"\\s*:\\s*\\{([^}]*)\\}").find(config.readText())?.groupValues?.get(1) ?: return emptyList()
            val byId = Regex("\"([^\"]+)\"\\s*:\\s*(\\d+)").findAll(map).associate { it.groupValues[2].toInt() to it.groupValues[1] }
            if (byId.size < 2 || byId.values.any { name -> name.all { it.isDigit() } }) return emptyList()
            val names = (0 until byId.size).map { byId[it] ?: return emptyList() }
            // A dialogue slot is the same reader, not a speaker to pick; it comes last, so the others keep their ids.
            val dialogue = dialogueSpeaker(voice) ?: return names
            return if (dialogue == names.lastIndex && names.size > 2) names.dropLast(1) else emptyList()
        }

        /**
         * The speaker id a voice reads quoted speech with, from its config's `granger` block (`"dialogue_speaker": 1`,
         * V4b), or null when it reads everything with one speaker.
         */
        fun dialogueSpeaker(voice: SherpaVoice): Int? {
            if (voice !is Piper) return null
            val config = File(voice.model.path + ".json").takeIf { it.isFile } ?: return null
            val block = Regex("\"granger\"\\s*:\\s*\\{([^}]*)\\}").find(config.readText())?.groupValues?.get(1) ?: return null
            return Regex("\"dialogue_speaker\"\\s*:\\s*(\\d+)").find(block)?.groupValues?.get(1)?.toIntOrNull()
        }

        /**
         * How a Piper voice wants to be sampled, from its config's `inference` block (`noise_scale`, `noise_w`,
         * `length_scale`, Piper's standard keys), so each voice ships its own settings (V4: Neal's pick for the
         * Granger British voices is 0.75 / 1.0). sherpa-onnx's defaults, which are Piper's, fill in what is missing.
         */
        fun inference(voice: SherpaVoice): Inference {
            if (voice !is Piper) return Inference()
            val config = File(voice.model.path + ".json").takeIf { it.isFile } ?: return Inference()
            val block = Regex("\"inference\"\\s*:\\s*\\{([^}]*)\\}").find(config.readText())?.groupValues?.get(1) ?: return Inference()
            fun value(key: String) = Regex("\"$key\"\\s*:\\s*([0-9.]+)").find(block)?.groupValues?.get(1)?.toFloatOrNull()
            val default = Inference()
            return Inference(
                noiseScale = value("noise_scale") ?: default.noiseScale,
                noiseScaleW = value("noise_w") ?: default.noiseScaleW,
                lengthScale = value("length_scale") ?: default.lengthScale,
            )
        }

        /** Every voice directly under [root], by id. */
        fun list(root: File): List<SherpaVoice> =
            root.listFiles().orEmpty().filter { it.isDirectory }.sortedBy { it.name }.mapNotNull(::detect)
    }
}
