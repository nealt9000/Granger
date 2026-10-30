package app.granger.voiceinput

import android.Manifest
import android.content.pm.PackageManager
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import app.granger.core.speech.Wav
import java.io.File
import org.json.JSONObject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Debug-only (Plan A0): what the phone's own speech recognizer supports, and the WAVs under `files/asr/` replayed
 * into it. Logs `Asr:` JSON lines under the Granger tag and appends them to `files/asr-spike-<time>.log`.
 * Start with `adb shell am start -n app.granger/.voiceinput.VoiceInputSpikeActivity`.
 *
 * Unattended runs take `--es run probe|download|replay|bias` (comma-separated allowed), `--es lang en-US`,
 * `--ez paced false` (replay as fast as the pipe takes it), `--ei tail 300` (ms of silence after each WAV), `--ez partials false`, `--ez segmented false` (a plain session; its final results come back empty), `--es bias names|library`
 * (`files/asr/bias-<name>.json`), `--es only q,n` (WAVs whose names start so). The run ends with an `Asr: auto done` line.
 * `RECORD_AUDIO` is asked for on first open; `adb shell pm grant app.granger android.permission.RECORD_AUDIO` also works.
 */
class VoiceInputSpikeActivity : ComponentActivity() {
    private val lines = mutableStateListOf<String>()
    private var running by mutableStateOf(false)
    private lateinit var logFile: File
    private lateinit var asrDir: File
    private lateinit var probe: RecognizerProbe
    private var tailMs = 1500
    private var partials = true
    // Plan A0 Task 1: only a segmented session returns final results over the pipe.
    private var segmented = true
    private val recorder = PhraseRecorder()
    private var recording by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // As in the voice spike: on top even over the lock screen, screen kept on, so runs are unattended and the
        // recognizer sees a foreground app.
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = getExternalFilesDir(null) ?: filesDir
        logFile = File(root, "asr-spike-${System.currentTimeMillis()}.log")
        asrDir = File(root, "asr").apply { mkdirs() }
        probe = RecognizerProbe(this, lifecycleScope)
        log("Asr: device ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.ID}")
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }

        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) { Screen() }
            }
        }

        if (savedInstanceState == null) intent.getStringExtra("run")?.let { runs ->
            val lang = intent.getStringExtra("lang") ?: "en-US"
            val paced = intent.getBooleanExtra("paced", true)
            tailMs = intent.getIntExtra("tail", tailMs)
            partials = intent.getBooleanExtra("partials", partials)
            segmented = intent.getBooleanExtra("segmented", segmented)
            val biasName = intent.getStringExtra("bias")
            val only = intent.getStringExtra("only")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
            launchRun {
                for (run in runs.split(',').map { it.trim() }) when (run) {
                    "probe" -> probeAll()
                    "download" -> download(lang)
                    "replay" -> replay(lang, paced, biasName, only)
                    "bias" -> biasCap(lang)
                    else -> log("Asr: unknown run $run")
                }
                log("Asr: auto done run=$runs")
            }
        }
    }

    private suspend fun probeAll() {
        log("Asr: services ${probe.services()}")
        for (lang in listOf("en-US", "en-GB")) log("Asr: support ${probe.support(lang)}")
    }

    private suspend fun download(lang: String) {
        var last = -1
        log("Asr: download ${probe.download(lang) { p -> if (p / 10 != last / 10) { last = p; log("Asr: download progress $p %") } }}")
        log("Asr: support ${probe.support(lang)}")
    }

    private fun wavs(): List<File> = asrDir.listFiles().orEmpty().filter { it.name.endsWith(".wav") }.sortedBy { it.name }

    /**
     * Every WAV under `files/asr/` (or those whose names start with one of [only]) through the recognizer. [biasName]
     * picks `files/asr/bias-<name>.json`, a JSON array of strings written by `tools/asr/bias_list.py`.
     */
    private suspend fun replay(lang: String, paced: Boolean, biasName: String? = null, only: List<String>? = null) {
        val files = wavs().filter { f -> only == null || only.any { f.name.startsWith(it) } }
        if (files.isEmpty()) { log("Asr: no WAVs in ${asrDir.path}"); return }
        val bias = biasName?.let { name ->
            val array = withContext(Dispatchers.IO) { org.json.JSONArray(File(asrDir, "bias-$name.json").readText()) }
            List(array.length()) { array.getString(it) }
        }
        log("Asr: replay start files=${files.size} bias=${biasName ?: "none"}(${bias?.size ?: 0}) paced=$paced tail=$tailMs segmented=$segmented")
        for (file in files) {
            val pcm = withContext(Dispatchers.IO) { Wav.read(file) }
            log("Asr: replay ${recognizeWithRetry(pcm, lang, bias, paced).put("file", file.name).put("biasName", biasName ?: "none")}")
        }
    }

    /**
     * Biasing lists of growing size, each holding the real names plus filler, replayed against the WAV that says
     * one of them (`probe-p3.wav`, "Wikivoyage", else the first): where the recognizer refuses the list, and where
     * the name stops being helped.
     */
    private suspend fun biasCap(lang: String) {
        val all = wavs()
        val file = all.firstOrNull { it.name.contains("p3") } ?: all.firstOrNull() ?: run { log("Asr: no WAVs in ${asrDir.path}"); return }
        val pcm = withContext(Dispatchers.IO) { Wav.read(file) }
        val names = listOf("Wikivoyage", "Meditations", "Marcus Aurelius")
        for (size in listOf(0, 3, 10, 100, 500, 1000, 5000)) {
            val bias = (names + List(maxOf(0, size - names.size)) { "Granger shelf ${it + 1}" }).take(size)
            log("Asr: bias ${recognizeWithRetry(pcm, lang, bias.takeIf { size > 0 }).put("file", file.name)}")
        }
    }

    /**
     * One recognition, retried once after a pause on `SERVER_DISCONNECTED`: a recognizer opened straight after the
     * last one closed sometimes finds the service still going down.
     */
    private suspend fun recognizeWithRetry(pcm: app.granger.core.speech.Pcm, lang: String, bias: List<String>?, paced: Boolean = true): JSONObject {
        delay(GAP_MS)
        val first = probe.recognize(pcm, lang, bias = bias, paced = paced, tailMs = tailMs, partials = partials, segmented = segmented)
        if (first.optString("error") != "SERVER_DISCONNECTED") return first
        delay(1_000)
        return probe.recognize(pcm, lang, bias = bias, paced = paced, tailMs = tailMs, partials = partials, segmented = segmented).put("retried", true)
    }

    private fun launchRun(block: suspend () -> Unit) {
        running = true
        lifecycleScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.e(TAG, "Asr: run failed", t)
                log("Asr: run failed: $t")
            } finally {
                running = false
            }
        }
    }

    @Composable
    private fun Screen() {
        Column(Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState())) {
            Text("WAVs in ${asrDir.path}: ${wavs().joinToString { it.name }.ifEmpty { "none" }}", fontSize = 11.sp)
            Text("Log: ${logFile.path}", fontSize = 11.sp)
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                Button(enabled = !running, onClick = { launchRun { probeAll() } }) { Text("Probe") }
                Spacer(Modifier.width(8.dp))
                Button(enabled = !running, onClick = { launchRun { download("en-US") } }) { Text("Download en-US") }
                Spacer(Modifier.width(8.dp))
                Button(enabled = !running, onClick = { launchRun { replay("en-US", paced = true) } }) { Text("Replay WAVs") }
                Spacer(Modifier.width(8.dp))
                Button(enabled = !running, onClick = { launchRun { biasCap("en-US") } }) { Text("Bias cap") }
            }
            Recorder()
            lines.forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 10.sp) }
        }
    }

    /**
     * Plan A0 Task 3: Neal reads each phrase from `files/asr/phrases.json` once. Record starts the microphone, Stop
     * saves `files/asr/<id>.wav` and moves on; Back and Skip move without recording, Again re-records.
     */
    @Composable
    private fun Recorder() {
        val phrases = androidx.compose.runtime.remember {
            runCatching { PhraseRecorder.load(File(asrDir, "phrases.json")) }.getOrDefault(emptyList())
        }
        if (phrases.isEmpty()) { Text("No phrases.json in ${asrDir.path}", Modifier.padding(top = 12.dp)); return }
        var at by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(phrases.indexOfFirst { !File(asrDir, "${it.id}.wav").exists() }.coerceAtLeast(0)) }
        var lastMs by androidx.compose.runtime.remember { mutableStateOf<Long?>(null) }
        val phrase = phrases[at]
        val done = phrases.count { File(asrDir, "${it.id}.wav").exists() }
        Column(Modifier.padding(top = 16.dp)) {
            Text("Reading: ${at + 1} of ${phrases.size} · recorded $done · ${phrase.id} (${phrase.kind})", fontSize = 12.sp)
            Text(phrase.text, Modifier.padding(vertical = 16.dp), fontSize = 26.sp, lineHeight = 32.sp)
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                if (!recording) {
                    Button(enabled = !running, onClick = {
                        recording = true
                        lifecycleScope.launch {
                            try {
                                val ms = recorder.record(File(asrDir, "${phrase.id}.wav"))
                                lastMs = ms
                                log("Asr: recorded ${phrase.id} ms=$ms")
                                if (at < phrases.lastIndex) at++
                            } catch (e: CancellationException) {
                                throw e
                            } catch (t: Throwable) {
                                log("Asr: recording failed: $t")
                            } finally {
                                recording = false
                            }
                        }
                    }) { Text(if (File(asrDir, "${phrase.id}.wav").exists()) "Again" else "Record") }
                } else {
                    Button(onClick = { recorder.stop() }) { Text("Stop and next") }
                }
                Spacer(Modifier.width(8.dp))
                androidx.compose.material3.OutlinedButton(enabled = !recording && at > 0, onClick = { at-- }) { Text("Back") }
                Spacer(Modifier.width(8.dp))
                androidx.compose.material3.OutlinedButton(enabled = !recording && at < phrases.lastIndex, onClick = { at++ }) { Text("Skip") }
            }
            lastMs?.let { Text("Last take: $it ms", fontSize = 11.sp) }
        }
    }

    private fun log(line: String) {
        Log.i(TAG, line)
        lines += line
        runCatching { logFile.appendText(line + "\n") }
    }

    private companion object {
        const val TAG = "Granger"
        const val GAP_MS = 500L
    }
}
