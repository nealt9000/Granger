package app.granger.voice

import app.granger.core.speech.Wav

import android.app.ActivityManager
import android.content.Intent
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import app.granger.core.ai.DeviceTiers
import app.granger.core.ai.LiteRtLlmEngine
import app.granger.core.ai.LlmBackend
import app.granger.core.ai.RssMemorySampler
import app.granger.core.ai.SystemMonoClock
import app.granger.core.ai.spike.SpikeModels
import app.granger.core.speech.SherpaVoice
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Debug-only (Plan V0): measures the phone's text-to-speech and the sherpa-onnx voices found under
 * `files/voices/<id>/` on the three fixed passages, with E2B resident and with music under the voice when asked.
 * Logs `Voice:` lines under the Granger tag and appends them to `files/voice-spike-<time>.log`; each passage's audio
 * is saved as a WAV beside it. Start with `adb shell am start -n app.granger/.voice.VoiceSpikeActivity`.
 *
 * Unattended runs take intent extras: `--es engine system|<voice id>|all` (comma-separated ids allowed),
 * `--ei threads 2`, `--ei speaker 0`, `--ei passes 2` (each pass speaks the three passages; the second is the warm
 * run), `--ez llm true` (E2B resident on the GPU first), `--ez music true`. The run ends with a `Voice: auto done` line.
 */
class VoiceSpikeActivity : ComponentActivity() {
    private val lines = mutableStateListOf<String>()
    private val wavs = mutableStateListOf<File>()
    private var running by mutableStateOf(false)
    private var llmResident by mutableStateOf(false)
    private var musicOn by mutableStateOf(false)
    private lateinit var logFile: File
    private lateinit var voicesDir: File
    private lateinit var runner: VoiceSpikeRunner
    private val player = PcmPlayer()
    private var llm: LiteRtLlmEngine? = null
    private var music: MediaPlayer? = null
    private val musicFile: File? get() = voicesDir.listFiles().orEmpty().firstOrNull { it.isFile && it.name.startsWith("music.") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Measurements are only meaningful with the screen on: once it goes dark the process is treated as
        // background and confined to the little cores, and the numbers degrade tenfold.
        // On top even over the lock screen: a focus request from an app that isn't on top is refused on Android 14+,
        // and the voice must be playing (so holding focus and a foreground service) before the screen is allowed
        // to go dark. The service run (Plan V1 Task 9) then lets it go dark; every other run keeps it on.
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        if (!intent.getBooleanExtra("service", false)) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = getExternalFilesDir(null) ?: filesDir
        logFile = File(root, "voice-spike-${System.currentTimeMillis()}.log")
        voicesDir = File(root, "voices")
        runner = VoiceSpikeRunner(RssMemorySampler(), SystemMonoClock, File(root, "voice-spike"))
        val totalMem = ActivityManager.MemoryInfo().also { (getSystemService(ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it) }.totalMem
        val soc = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else "unknown"
        val deviceLine = "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} · SoC $soc · totalMem $totalMem B · tier ${DeviceTiers.forTotalRam(totalMem)}"
        log("Voice: device $deviceLine")
        wavs += File(root, "voice-spike").listFiles().orEmpty().filter { it.name.endsWith(".wav") }.sortedBy { it.name }

        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    Screen(deviceLine)
                }
            }
        }

        if (savedInstanceState == null) {
            if (intent.getBooleanExtra("service", false)) serviceRun(intent) else intent.getStringExtra("engine")?.let { autoRun(it, intent) }
        }
    }

    /**
     * Plan V1 Task 9: the three passages read through the real player (`PlayerHub.readAloud`, so the voice runs under
     * `PlaybackService`'s foreground service) with the screen free to go dark. `--es voice <id>` picks a voice
     * (`granger:<dir>` or `system:<engine>:<name>`), else the preferred one. Per-sentence costs come from the
     * engine's `GrangerVoice` log lines; this logs the progress and `Voice: auto done` when the reading ends.
     */
    private fun serviceRun(intent: Intent) {
        val hub = (application as app.granger.GrangerApp).container.playerHub
        val voiceId = intent.getStringExtra("voice")?.let(app.granger.core.speech.VoiceId::decode)
        // The three passages are about 2.5 minutes of speech; repeated (default three times) the run outlasts five minutes with the screen off.
        val repeat = intent.getIntExtra("repeat", 3).coerceAtLeast(1)
        val text = List(repeat) { VoiceSpikePassages.ALL.joinToString("\n\n") { it.text } }.joinToString("\n\n")
        val source = app.granger.core.speech.TextReadSource("spike:service", "Voice spike passages", text)
        log("Voice: service start voice=${voiceId?.encode() ?: "preferred"} repeat=$repeat sentences=${source.size}")
        running = true
        lifecycleScope.launch {
            val started = SystemMonoClock.nanos()
            hub.readAloud(source, null, voiceId)
            var last = -1
            var seenVoice = false
            kotlinx.coroutines.flow.combine(hub.ui, hub.voiceProgress) { ui, p -> ui to p }.collect { (ui, p) ->
                val now = ui.now as? app.granger.core.player.NowPlaying.Voice
                if (now != null) seenVoice = true
                if (p != null && p.index != last) {
                    last = p.index
                    log("Voice: service sentence=${p.index} chars=${p.utterance.text.length} atMs=${(SystemMonoClock.nanos() - started) / 1_000_000} playing=${ui.playing}")
                }
                if (now?.ended == true || (seenVoice && now == null)) {
                    log("Voice: service ended=${now?.ended} atMs=${(SystemMonoClock.nanos() - started) / 1_000_000}")
                    log("Voice: auto done service=true")
                    running = false
                    return@collect
                }
            }
        }
    }

    /** Builds the engine named [name]: `system`, or a voice directory's id. */
    private fun engineFor(name: String, threads: Int, speaker: Int): SpikeEngine? = when (name) {
        "system" -> SystemSpikeEngine(this, cacheDir)
        else -> SherpaVoice.list(voicesDir).firstOrNull { it.id == name }?.let { SherpaSpikeEngine(it, threads, speaker) }
    }

    private fun autoRun(engines: String, intent: Intent) {
        val threads = intent.getIntExtra("threads", 2)
        val speaker = intent.getIntExtra("speaker", 0)
        val passes = intent.getIntExtra("passes", 1).coerceAtLeast(1)
        val withLlm = intent.getBooleanExtra("llm", false)
        val withMusic = intent.getBooleanExtra("music", false)
        val names = if (engines == "all") listOf("system") + SherpaVoice.list(voicesDir).map { it.id } else engines.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        // `--es text "..."` speaks that instead of the passages (a pronunciation check: the WAV is voice-<engine>-t<threads>-custom.wav).
        val passages = intent.getStringExtra("text")?.takeIf { it.isNotBlank() }?.let { listOf(Passage("custom", "Custom text", it)) } ?: VoiceSpikePassages.ALL
        running = true
        lifecycleScope.launch {
            try {
                log("Voice: auto start engines=$names threads=$threads speaker=$speaker passes=$passes llm=$withLlm music=$withMusic")
                if (withLlm && !llmResident) { loadLlm(); llmResident = llm != null }
                if (withMusic && !musicOn) toggleMusic()
                for (pass in 1..passes) for (name in names) {
                    val make = { engineFor(name, threads, speaker) ?: error("no engine named $name under ${voicesDir.path}") }
                    runOnce(make, threads, pass, passages)
                }
                log("Voice: auto done engines=$names threads=$threads passes=$passes llm=$llmResident music=$musicOn")
            } finally {
                running = false
            }
        }
    }

    /** Loads one engine, speaks the three passages, logs the rows and the summaries, closes it. Never throws. */
    private suspend fun runOnce(make: () -> SpikeEngine, threads: Int, pass: Int, passages: List<Passage> = VoiceSpikePassages.ALL) {
        try {
            val engine = make()
            try {
                log("Voice: engine=${engine.name} threads=$threads pass=$pass start llmResident=$llmResident music=$musicOn")
                log(VoiceLog.line(runner.load(engine, threads)))
                val rows = mutableListOf<SentenceRow>()
                for (passage in passages) {
                    rows += runner.run(engine, threads, passage) { row -> log(VoiceLog.line(row)) }
                    val wav = runner.wavFile(engine.name, threads, passage.id)
                    if (wav.exists() && wav !in wavs) wavs += wav
                }
                VoiceLog.summary(rows).forEach(::log)
                log("Voice: engine=${engine.name} threads=$threads pass=$pass done llmResident=$llmResident music=$musicOn")
            } finally {
                withContext(Dispatchers.Default) { engine.close() }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.e(TAG, "Voice: run failed", t)
            log("Voice: run failed: $t")
        }
    }

    private fun toggleMusic() {
        val file = musicFile
        if (musicOn) {
            music?.release(); music = null; musicOn = false
            log("Voice: music off")
        } else if (file != null) {
            music = MediaPlayer().apply {
                setDataSource(file.path); isLooping = true; setVolume(0.3f, 0.3f); prepare(); start()
            }
            musicOn = true
            log("Voice: music under the voice at 30 %: ${file.name}")
        } else {
            log("Voice: no voices/music.* file to play")
        }
    }

    @Composable
    private fun Screen(deviceLine: String) {
        var threads by remember { mutableIntStateOf(2) }
        var speaker by remember { mutableIntStateOf(0) }
        val voices = remember { SherpaVoice.list(voicesDir) }
        val hasMusic = remember { musicFile != null }

        fun runEngine(make: () -> SpikeEngine) {
            running = true
            lifecycleScope.launch {
                try { runOnce(make, threads, 1) } finally { running = false }
            }
        }

        Column(Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState())) {
            Text(deviceLine, fontSize = 11.sp)
            Text("Voices in ${voicesDir.path}: ${if (voices.isEmpty()) "none (push a sherpa-onnx voice directory there)" else voices.joinToString { it.id }}", fontSize = 11.sp)
            Text("Log: ${logFile.path}", fontSize = 11.sp)

            Row(Modifier.horizontalScroll(rememberScrollState())) {
                Text("Threads:", Modifier.padding(end = 4.dp))
                for (n in listOf(1, 2, 4)) {
                    FilterChip(selected = threads == n, onClick = { threads = n }, label = { Text("$n") }, enabled = !running)
                    Spacer(Modifier.width(4.dp))
                }
                Spacer(Modifier.width(12.dp))
                Text("Speaker:", Modifier.padding(end = 4.dp))
                OutlinedButton(onClick = { if (speaker > 0) speaker-- }, enabled = !running) { Text("−") }
                Text("$speaker", Modifier.padding(horizontal = 6.dp))
                OutlinedButton(onClick = { speaker++ }, enabled = !running) { Text("+") }
            }

            Row(Modifier.horizontalScroll(rememberScrollState())) {
                FilterChip(
                    selected = llmResident,
                    enabled = !running,
                    onClick = {
                        running = true
                        lifecycleScope.launch {
                            try {
                                if (llmResident) { withContext(Dispatchers.IO) { llm?.close() }; llm = null; llmResident = false; log("Voice: E2B released") }
                                else { loadLlm(); llmResident = llm != null }
                            } finally { running = false }
                        }
                    },
                    label = { Text(if (llmResident) "E2B resident (GPU)" else "Load E2B (GPU)") },
                )
                Spacer(Modifier.width(8.dp))
                FilterChip(
                    selected = musicOn,
                    enabled = !running && hasMusic,
                    onClick = { toggleMusic() },
                    label = { Text(if (!hasMusic) "No voices/music.* file" else if (musicOn) "Music on (30 %)" else "Music under the voice") },
                )
            }

            Row(Modifier.horizontalScroll(rememberScrollState())) {
                Button(enabled = !running, onClick = { runEngine { SystemSpikeEngine(this@VoiceSpikeActivity, cacheDir) } }) { Text("Run system") }
                Spacer(Modifier.width(8.dp))
                for (voice in voices) {
                    Button(enabled = !running, onClick = { runEngine { SherpaSpikeEngine(voice, threads, speaker) } }) { Text("Run ${voice.id}") }
                    Spacer(Modifier.width(8.dp))
                }
            }

            if (wavs.isNotEmpty()) {
                Text("Saved audio (tap to play, Stop to stop):", Modifier.padding(top = 8.dp), fontSize = 12.sp)
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    OutlinedButton(onClick = { player.stop() }) { Text("Stop") }
                    Spacer(Modifier.width(8.dp))
                    for (wav in wavs) {
                        OutlinedButton(onClick = { lifecycleScope.launch { runCatching { player.play(withContext(Dispatchers.IO) { Wav.read(wav) }) }.onFailure { log("Voice: can't play ${wav.name}: $it") } } }) {
                            Text(wav.name.removePrefix("voice-").removeSuffix(".wav"), fontSize = 11.sp)
                        }
                        Spacer(Modifier.width(4.dp))
                    }
                }
            }

            lines.forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 10.sp) }
        }
    }

    /** Loads the GPU E2B from wherever the app keeps models and warms it with one short reply. */
    private suspend fun loadLlm() {
        val file = withContext(Dispatchers.IO) {
            getExternalFilesDirs(null).filterNotNull().asSequence()
                .flatMap { it.walkTopDown().maxDepth(3) }
                .firstOrNull { it.isFile && it.name == SpikeModels.SMALL_GPU }
        }
        if (file == null) { log("Voice: no ${SpikeModels.SMALL_GPU} under the app's files; download E2B in Settings → Models first"); return }
        val start = SystemMonoClock.nanos()
        val (engine, rss) = RssMemorySampler().peakRssMb {
            withContext(Dispatchers.IO) {
                LiteRtLlmEngine(file.path, LlmBackend.GPU, cacheDir.path).also { e ->
                    e.load()
                    e.generate("Reply with one short sentence: hello.", 16).first()
                }
            }
        }
        llm = engine
        log("Voice: E2B resident loadMs=${(SystemMonoClock.nanos() - start) / 1_000_000} rssMb=$rss file=${file.path}")
    }

    private fun log(line: String) {
        Log.i(TAG, line)
        lines += line
        runCatching { logFile.appendText(line + "\n") }
    }

    override fun onDestroy() {
        player.stop()
        music?.release()
        llm?.close()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "Granger"
    }
}
