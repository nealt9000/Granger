package app.granger.voice

import android.app.ActivityManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.util.Log
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
 */
class VoiceSpikeActivity : ComponentActivity() {
    private val lines = mutableStateListOf<String>()
    private val wavs = mutableStateListOf<File>()
    private lateinit var logFile: File
    private val player = PcmPlayer()
    private var llm: LiteRtLlmEngine? = null
    private var music: MediaPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = getExternalFilesDir(null) ?: filesDir
        logFile = File(root, "voice-spike-${System.currentTimeMillis()}.log")
        val voicesDir = File(root, "voices")
        val runner = VoiceSpikeRunner(RssMemorySampler(), SystemMonoClock, File(root, "voice-spike"))
        val totalMem = ActivityManager.MemoryInfo().also { (getSystemService(ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it) }.totalMem
        val soc = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else "unknown"
        val deviceLine = "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} · SoC $soc · totalMem $totalMem B · tier ${DeviceTiers.forTotalRam(totalMem)}"
        log("Voice: device $deviceLine")
        wavs += File(root, "voice-spike").listFiles().orEmpty().filter { it.name.endsWith(".wav") }.sortedBy { it.name }

        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    Screen(deviceLine, voicesDir, runner)
                }
            }
        }
    }

    @Composable
    private fun Screen(deviceLine: String, voicesDir: File, runner: VoiceSpikeRunner) {
        var running by remember { mutableStateOf(false) }
        var threads by remember { mutableIntStateOf(2) }
        var speaker by remember { mutableIntStateOf(0) }
        var llmResident by remember { mutableStateOf(false) }
        var musicOn by remember { mutableStateOf(false) }
        val voices = remember { SherpaVoice.list(voicesDir) }
        val musicFile = remember { voicesDir.listFiles().orEmpty().firstOrNull { it.isFile && it.name.startsWith("music.") } }

        fun runEngine(make: () -> SpikeEngine) {
            running = true
            lifecycleScope.launch {
                try {
                    val engine = make()
                    try {
                        log(VoiceLog.line(runner.load(engine, threads)))
                        val rows = mutableListOf<SentenceRow>()
                        for (passage in VoiceSpikePassages.ALL) {
                            rows += runner.run(engine, threads, passage) { row -> log(VoiceLog.line(row)) }
                            val wav = runner.wavFile(engine.name, threads, passage.id)
                            if (wav.exists() && wav !in wavs) wavs += wav
                        }
                        VoiceLog.summary(rows).forEach(::log)
                        log("Voice: engine=${engine.name} done llmResident=$llmResident music=$musicOn")
                    } finally {
                        withContext(Dispatchers.Default) { engine.close() }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Log.e(TAG, "Voice: run failed", t)
                    log("Voice: run failed: $t")
                } finally {
                    running = false
                }
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
                    enabled = !running && musicFile != null,
                    onClick = {
                        val file = musicFile
                        if (musicOn) { music?.release(); music = null; musicOn = false }
                        else if (file != null) {
                            music = MediaPlayer().apply {
                                setDataSource(file.path); isLooping = true; setVolume(0.3f, 0.3f); prepare(); start()
                            }
                            musicOn = true
                            log("Voice: music under the voice at 30 %: ${file.name}")
                        }
                    },
                    label = { Text(if (musicFile == null) "No voices/music.* file" else if (musicOn) "Music on (30 %)" else "Music under the voice") },
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
