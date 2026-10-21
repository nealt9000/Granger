package app.granger.voice

import app.granger.core.ai.MemorySampler
import app.granger.core.ai.MonoClock
import java.io.File
import java.util.Locale

/** One sentence of one passage through one engine. [firstAudioMs] is null when the engine couldn't say. */
data class SentenceRow(
    val engine: String,
    val threads: Int,
    val passage: String,
    val index: Int,
    val chars: Int,
    val firstAudioMs: Long?,
    val synthMs: Long,
    val audioMs: Long,
    val rssMb: Int,
    val error: String? = null,
) {
    /** Synthesis seconds per second of audio; below 1 is faster than real time. NaN with no audio. */
    val rtf: Double get() = VoiceLog.rtf(synthMs, audioMs)
}

data class LoadRow(val engine: String, val threads: Int, val loadMs: Long, val rssMb: Int, val info: String?)

/** The `Voice:` log lines the results doc is built from. */
object VoiceLog {
    fun rtf(synthMs: Long, audioMs: Long): Double = if (audioMs <= 0) Double.NaN else synthMs.toDouble() / audioMs

    fun line(r: SentenceRow): String = buildString {
        append("Voice: engine=${r.engine} threads=${r.threads} passage=${r.passage} sentence=${r.index} chars=${r.chars}")
        append(" firstAudioMs=${r.firstAudioMs ?: "-"} synthMs=${r.synthMs} audioMs=${r.audioMs} rtf=${fmt(r.rtf)} rssMb=${r.rssMb}")
        r.error?.let { append(" error=$it") }
    }

    fun line(l: LoadRow): String =
        "Voice: engine=${l.engine} threads=${l.threads} load loadMs=${l.loadMs} rssMb=${l.rssMb}" + (l.info?.let { " $it" } ?: "")

    /** One line per passage: totals, the overall real-time factor, the slowest first audio and the peak memory. */
    fun summary(rows: List<SentenceRow>): List<String> =
        rows.groupBy { Triple(it.engine, it.threads, it.passage) }.map { (key, group) ->
            val (engine, threads, passage) = key
            val synth = group.sumOf { it.synthMs }
            val audio = group.sumOf { it.audioMs }
            val firstMax = group.mapNotNull { it.firstAudioMs }.maxOrNull()
            val errors = group.count { it.error != null }
            "Voice: engine=$engine threads=$threads passage=$passage summary sentences=${group.size} synthMs=$synth audioMs=$audio" +
                " rtf=${fmt(rtf(synth, audio))} firstAudioMaxMs=${firstMax ?: "-"} rssMaxMb=${group.maxOf { it.rssMb }}" +
                if (errors > 0) " errors=$errors" else ""
        }

    private fun fmt(d: Double) = if (d.isNaN()) "-" else String.format(Locale.ROOT, "%.2f", d)
}

/**
 * Drives one engine through the passages, timing each sentence and sampling memory, and writes each passage's audio
 * to `voice-<engine>-<passage>.wav` in [out] for the listening session.
 */
class VoiceSpikeRunner(private val memory: MemorySampler, private val clock: MonoClock, private val out: File) {
    suspend fun load(engine: SpikeEngine, threads: Int): LoadRow {
        val start = clock.nanos()
        val (info, rss) = memory.peakRssMb { engine.load() }
        return LoadRow(engine.name, threads, (clock.nanos() - start) / 1_000_000, rss, info)
    }

    suspend fun run(engine: SpikeEngine, threads: Int, passage: Passage, onRow: (SentenceRow) -> Unit): List<SentenceRow> {
        val sentences = Sentences.split(passage.text)
        val rows = mutableListOf<SentenceRow>()
        val audio = mutableListOf<FloatArray>()
        var sampleRate = 0
        for ((i, sentence) in sentences.withIndex()) {
            val start = clock.nanos()
            var firstAt: Long? = null
            val (result, rss) = memory.peakRssMb {
                runCatching { engine.synthesize(sentence) { firstAt = clock.nanos() } }
            }
            val synthMs = (clock.nanos() - start) / 1_000_000
            val row = result.fold(
                onSuccess = { pcm ->
                    audio += pcm.samples
                    sampleRate = pcm.sampleRate
                    SentenceRow(engine.name, threads, passage.id, i, sentence.length, firstAt?.let { (it - start) / 1_000_000 }, synthMs, pcm.durationMs, rss)
                },
                onFailure = { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    SentenceRow(engine.name, threads, passage.id, i, sentence.length, null, synthMs, 0, rss, error = e.toString().take(120))
                },
            )
            rows += row
            onRow(row)
        }
        if (audio.isNotEmpty() && sampleRate > 0) {
            out.mkdirs()
            Wav.write(Pcm(Wav.concat(audio), sampleRate), wavFile(engine.name, threads, passage.id))
        }
        return rows
    }

    fun wavFile(engine: String, threads: Int, passage: String): File =
        File(out, "voice-${engine.replace(Regex("[^A-Za-z0-9_.#-]"), "_")}-t$threads-$passage.wav")
}
