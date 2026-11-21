package app.granger.core.speech

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString

/**
 * How a Granger voice directory is recognised, read and named, on both phones (Plan I5 Task 3). A voice is a
 * directory as sherpa-onnx's `tts-models` release unpacks it, named by its id (`vits-piper-en_US-ljspeech-medium`,
 * `kokoro-int8-multi-lang-v1_1`, …). Android's [SherpaVoice] wraps these rules in `File`s for the engine; the voice
 * list ([InstalledVoices]) reads directories through [list] on either phone.
 */
object PiperVoices {
    /** One name in a voice directory. */
    data class Entry(val name: String, val isDirectory: Boolean, val isFile: Boolean = !isDirectory)

    /** Which of a directory's names make up a voice. */
    sealed interface Layout {
        /** The ONNX model file. */
        val model: String
        val tokens: String
        /** The phonemizer's data directory, `espeak-ng-data/`. */
        val espeakData: String

        /** A Piper (VITS) voice: one `.onnx` model and `tokens.txt`. */
        data class Piper(override val model: String, override val tokens: String, override val espeakData: String) : Layout

        /**
         * A Kokoro voice: the model, its speaker table `voices.bin`, `tokens.txt`, and for the multi-language builds
         * one or more `lexicon-*.txt` files (joined for the engine) and a `dict/` directory.
         */
        data class Kokoro(
            override val model: String,
            val voices: String,
            override val tokens: String,
            override val espeakData: String,
            val lexicons: List<String>,
            val dictDir: String?,
        ) : Layout
    }

    /** A voice found on disk: its id (the directory's name), the directory's absolute path and its layout. */
    data class Found(val id: String, val dir: String, val layout: Layout)

    /** A VITS voice's sampling: more [noiseScale] is livelier and adds artifacts, more [noiseScaleW] varies timing. */
    data class Inference(val noiseScale: Float = 0.667f, val noiseScaleW: Float = 0.8f, val lengthScale: Float = 1f)

    /** The voice [entries] (a directory's listing, in listing order) make up, or null when they hold no recognisable voice. */
    fun detect(entries: List<Entry>): Layout? {
        val tokens = entries.firstOrNull { it.name == "tokens.txt" } ?: return null
        val espeak = entries.firstOrNull { it.isDirectory && it.name == "espeak-ng-data" } ?: return null
        val voices = entries.firstOrNull { it.name == "voices.bin" }
        val models = entries.filter { it.isFile && it.name.endsWith(".onnx") }
        if (voices != null) {
            // Prefer the int8 model when a directory holds both; it is the smaller one.
            val model = models.firstOrNull { "int8" in it.name } ?: models.firstOrNull() ?: return null
            return Layout.Kokoro(
                model = model.name,
                voices = voices.name,
                tokens = tokens.name,
                espeakData = espeak.name,
                lexicons = lexicons(entries),
                dictDir = entries.firstOrNull { it.isDirectory && it.name == "dict" }?.name,
            )
        }
        val model = models.singleOrNull() ?: return null
        return Layout.Piper(model.name, tokens.name, espeak.name)
    }

    /**
     * The lexicons a Kokoro build ships, one per language, US English preferred over British when both are
     * there (sherpa-onnx takes one English lexicon; the multi-language builds carry `lexicon-us-en.txt`,
     * `lexicon-gb-en.txt` and `lexicon-zh.txt`).
     */
    private fun lexicons(entries: List<Entry>): List<String> {
        val all = entries.filter { it.isFile && it.name.startsWith("lexicon") && it.name.endsWith(".txt") }.map { it.name }.sorted()
        return if (all.any { it == "lexicon-us-en.txt" }) all.filterNot { it == "lexicon-gb-en.txt" } else all
    }

    /** The Piper config beside a model: `<model>.onnx.json`. */
    fun configName(model: String): String = "$model.json"

    /**
     * The speakers' names by speaker id, from a Piper [config]'s `speaker_id_map`, or empty when the voice has one
     * speaker or its speakers are only reader numbers (a LibriTTS voice as trained): those are listed once, as speaker 0.
     */
    fun speakerNames(config: String?): List<String> {
        config ?: return emptyList()
        val map = Regex("\"speaker_id_map\"\\s*:\\s*\\{([^}]*)\\}").find(config)?.groupValues?.get(1) ?: return emptyList()
        val byId = Regex("\"([^\"]+)\"\\s*:\\s*(\\d+)").findAll(map).associate { it.groupValues[2].toInt() to it.groupValues[1] }
        if (byId.size < 2 || byId.values.any { name -> name.all { it.isDigit() } }) return emptyList()
        val names = (0 until byId.size).map { byId[it] ?: return emptyList() }
        // A dialogue slot is the same reader, not a speaker to pick; it comes last, so the others keep their ids.
        val dialogue = dialogueSpeaker(config) ?: return names
        return if (dialogue == names.lastIndex && names.size > 2) names.dropLast(1) else emptyList()
    }

    /**
     * The speaker id a voice reads quoted speech with, from its [config]'s `granger` block (`"dialogue_speaker": 1`,
     * V4b), or null when it reads everything with one speaker.
     */
    fun dialogueSpeaker(config: String?): Int? {
        config ?: return null
        val block = Regex("\"granger\"\\s*:\\s*\\{([^}]*)\\}").find(config)?.groupValues?.get(1) ?: return null
        return Regex("\"dialogue_speaker\"\\s*:\\s*(\\d+)").find(block)?.groupValues?.get(1)?.toIntOrNull()
    }

    /**
     * How a Piper voice wants to be sampled, from its [config]'s `inference` block (`noise_scale`, `noise_w`,
     * `length_scale`, Piper's standard keys), so each voice ships its own settings (V4: Neal's pick for the
     * Granger British voices is 0.75 / 1.0). sherpa-onnx's defaults, which are Piper's, fill in what is missing.
     */
    fun inference(config: String?): Inference {
        config ?: return Inference()
        val block = Regex("\"inference\"\\s*:\\s*\\{([^}]*)\\}").find(config)?.groupValues?.get(1) ?: return Inference()
        fun value(key: String) = Regex("\"$key\"\\s*:\\s*([0-9.]+)").find(block)?.groupValues?.get(1)?.toFloatOrNull()
        val default = Inference()
        return Inference(
            noiseScale = value("noise_scale") ?: default.noiseScale,
            noiseScaleW = value("noise_w") ?: default.noiseScaleW,
            lengthScale = value("length_scale") ?: default.lengthScale,
        )
    }

    // ---- on disk (kotlinx-io: java.io on Android, POSIX on the iPhone) ----

    /** Every voice directly under [root] (an absolute path), by id; empty when [root] isn't there. */
    fun list(root: String): List<Found> = runCatching {
        val rootPath = Path(root)
        val meta = SystemFileSystem.metadataOrNull(rootPath)
        if (meta == null || !meta.isDirectory) return@runCatching emptyList()
        SystemFileSystem.list(rootPath)
            .filter { SystemFileSystem.metadataOrNull(it)?.isDirectory == true }
            .sortedBy { it.name }
            .mapNotNull { found(it) }
    }.getOrDefault(emptyList())

    /** The voice in directory [dir], or null when it holds none. */
    fun detect(dir: String): Found? = runCatching { found(Path(dir)) }.getOrNull()

    private fun found(dir: Path): Found? {
        if (SystemFileSystem.metadataOrNull(dir)?.isDirectory != true) return null
        val entries = SystemFileSystem.list(dir).map { child ->
            val m = SystemFileSystem.metadataOrNull(child)
            Entry(child.name, isDirectory = m?.isDirectory == true, isFile = m?.isRegularFile == true)
        }
        val layout = detect(entries) ?: return null
        return Found(dir.name, dir.toString(), layout)
    }

    /** A found voice's Piper config text, or null for a Kokoro voice or a Piper voice without one. */
    fun config(voice: Found): String? {
        if (voice.layout !is Layout.Piper) return null
        val file = Path(voice.dir, configName(voice.layout.model))
        return runCatching {
            if (SystemFileSystem.metadataOrNull(file)?.isRegularFile != true) null
            else SystemFileSystem.source(file).buffered().use { it.readString() }
        }.getOrNull()
    }

    fun speakerNames(voice: Found): List<String> = speakerNames(config(voice))

    // ---- names ----

    /** "vits-piper-en_US-ljspeech-medium" → "ljspeech"; anything unexpected stays whole. */
    fun shortName(dir: String): String {
        val parts = dir.split('-')
        val locale = parts.indexOfFirst { it.matches(Regex("[a-z]{2}_[A-Z]{2}")) }
        return if (locale >= 0 && locale + 1 < parts.size) parts[locale + 1] else dir
    }

    /** "Granger voice (ljspeech)"; Granger's own voices drop the prefix: `…-granger_nanny-medium` → "Granger voice (Nanny)". */
    fun title(dir: String, titles: VoiceTitles): String {
        val name = shortName(dir)
        return titles.granger(if (name.startsWith("granger_")) name.removePrefix("granger_").replaceFirstChar { it.uppercase() } else name)
    }

    /** A named speaker's title: "Granger voice (Maggie)". */
    fun speakerTitle(name: String, titles: VoiceTitles): String = titles.granger(name)
}
