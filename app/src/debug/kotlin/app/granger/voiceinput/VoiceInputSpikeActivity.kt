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
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Debug-only (Plan A0): what the phone's own speech recognizer supports, and the WAVs under `files/asr/` replayed
 * into it. Logs `Asr:` JSON lines under the Granger tag and appends them to `files/asr-spike-<time>.log`.
 * Start with `adb shell am start -n app.granger/.voiceinput.VoiceInputSpikeActivity`.
 *
 * Unattended runs take `--es run probe|download|replay|bias` (comma-separated allowed), `--es lang en-US`,
 * `--ez paced false` (replay as fast as the pipe takes it). The run ends with an `Asr: auto done` line.
 * `RECORD_AUDIO` is asked for on first open; `adb shell pm grant app.granger android.permission.RECORD_AUDIO` also works.
 */
class VoiceInputSpikeActivity : ComponentActivity() {
    private val lines = mutableStateListOf<String>()
    private var running by mutableStateOf(false)
    private lateinit var logFile: File
    private lateinit var asrDir: File
    private lateinit var probe: RecognizerProbe

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
            launchRun {
                for (run in runs.split(',').map { it.trim() }) when (run) {
                    "probe" -> probeAll()
                    "download" -> download(lang)
                    "replay" -> replay(lang, paced)
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

    private suspend fun replay(lang: String, paced: Boolean) {
        val files = wavs()
        if (files.isEmpty()) { log("Asr: no WAVs in ${asrDir.path}"); return }
        for (file in files) {
            val pcm = withContext(Dispatchers.IO) { Wav.read(file) }
            log("Asr: replay ${probe.recognize(pcm, lang, paced = paced).put("file", file.name)}")
        }
    }

    /** The probe WAV with biasing lists of growing size, to find where the recognizer refuses or stops answering. */
    private suspend fun biasCap(lang: String) {
        val file = wavs().firstOrNull() ?: run { log("Asr: no WAVs in ${asrDir.path}"); return }
        val pcm = withContext(Dispatchers.IO) { Wav.read(file) }
        for (size in listOf(0, 10, 100, 500, 1000, 5000)) {
            val bias = List(size) { "Granger shelf ${it + 1}" }
            log("Asr: bias ${probe.recognize(pcm, lang, bias = bias.takeIf { size > 0 }).put("file", file.name)}")
        }
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
            lines.forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 10.sp) }
        }
    }

    private fun log(line: String) {
        Log.i(TAG, line)
        lines += line
        runCatching { logFile.appendText(line + "\n") }
    }

    private companion object {
        const val TAG = "Granger"
    }
}
