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
        /** The voice in [dir], or null when it holds no recognisable voice ([PiperVoices.detect]'s rules). */
        fun detect(dir: File): SherpaVoice? {
            if (!dir.isDirectory) return null
            val files = dir.listFiles().orEmpty()
            val layout = PiperVoices.detect(files.map { PiperVoices.Entry(it.name, it.isDirectory, it.isFile) }) ?: return null
            return when (layout) {
                is PiperVoices.Layout.Piper -> Piper(dir, File(dir, layout.model), File(dir, layout.tokens), File(dir, layout.espeakData))
                is PiperVoices.Layout.Kokoro -> Kokoro(
                    dir = dir,
                    model = File(dir, layout.model),
                    voices = File(dir, layout.voices),
                    tokens = File(dir, layout.tokens),
                    espeakData = File(dir, layout.espeakData),
                    lexicons = layout.lexicons.map { File(dir, it) },
                    dictDir = layout.dictDir?.let { File(dir, it) },
                )
            }
        }

        /** A Piper voice's config text (`<model>.onnx.json`), or null. */
        private fun config(voice: SherpaVoice): String? {
            if (voice !is Piper) return null
            return File(voice.model.path + ".json").takeIf { it.isFile }?.readText()
        }

        /** The speakers' names by speaker id ([PiperVoices.speakerNames]). */
        fun speakerNames(voice: SherpaVoice): List<String> = PiperVoices.speakerNames(config(voice))

        /** The speaker id a voice reads quoted speech with ([PiperVoices.dialogueSpeaker]), or null. */
        fun dialogueSpeaker(voice: SherpaVoice): Int? = PiperVoices.dialogueSpeaker(config(voice))

        /** How a Piper voice wants to be sampled ([PiperVoices.inference]). */
        fun inference(voice: SherpaVoice): Inference =
            PiperVoices.inference(config(voice)).let { Inference(it.noiseScale, it.noiseScaleW, it.lengthScale) }

        /** Every voice directly under [root], by id. */
        fun list(root: File): List<SherpaVoice> =
            root.listFiles().orEmpty().filter { it.isDirectory }.sortedBy { it.name }.mapNotNull(::detect)
    }
}
