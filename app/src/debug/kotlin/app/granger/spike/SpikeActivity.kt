package app.granger.spike

import android.app.ActivityManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.granger.core.ai.DeviceTiers
import app.granger.core.ai.LiteRtLlmEngine
import app.granger.core.ai.LlmBackend
import app.granger.core.ai.LiteRtTextEmbedder
import app.granger.core.ai.RssMemorySampler
import app.granger.core.ai.SystemMonoClock
import app.granger.core.ai.spike.SpikeConfig
import app.granger.core.ai.spike.SpikeModels
import app.granger.core.ai.spike.SpikeReport
import app.granger.core.ai.spike.SpikeRunner
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Debug-only: runs the model spike on this phone and saves a Markdown report. */
class SpikeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val totalMem = ActivityManager.MemoryInfo()
            .also { (getSystemService(ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it) }
            .totalMem
        val soc = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else "unknown"
        val deviceLine = "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} · " +
            "SoC $soc · totalMem $totalMem B · tier ${DeviceTiers.forTotalRam(totalMem)}"
        val modelsDir = findModelsDir()
        val runner = SpikeRunner(
            llmFactory = { path, backend -> LiteRtLlmEngine(path, backend, cacheDir.path) },
            embedderFactory = { model -> LiteRtTextEmbedder(model, cacheDir.path) },
            memory = RssMemorySampler(),
            clock = SystemMonoClock,
            fileExists = { File(it).canRead() },
        )
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    SpikeScreen(deviceLine, modelsDir, runner, ::newReportFile)
                }
            }
        }
    }

    /** Prefers the app's own files dir; falls back to /data/local/tmp if adb could not write there. */
    private fun findModelsDir(): File {
        val candidates = listOfNotNull(getExternalFilesDir("models"), File("/data/local/tmp/granger-models"))
        return candidates.firstOrNull { File(it, SpikeModels.SMALL_CPU).canRead() } ?: candidates.first()
    }

    private fun newReportFile(backend: LlmBackend): File =
        File(getExternalFilesDir(null), "spike-${backend.name.lowercase()}-${System.currentTimeMillis()}.md")
}

@Composable
private fun SpikeScreen(
    deviceLine: String,
    modelsDir: File,
    runner: SpikeRunner,
    newReportFile: (LlmBackend) -> File,
) {
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    val log = remember { mutableStateListOf<String>() }

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Text(deviceLine)
        Text("Models: ${modelsDir.path}")
        Row {
            LlmBackend.entries.forEach { backend ->
                Button(
                    enabled = !running,
                    onClick = {
                        running = true
                        scope.launch {
                            try {
                                val header = "$deviceLine · $backend"
                                val file = newReportFile(backend)
                                // Rows are appended as measured, so a crash or low-memory kill keeps partial results.
                                withContext(Dispatchers.IO) { file.writeText(SpikeReport.toMarkdown(header, emptyList())) }
                                val rows = withContext(Dispatchers.Default) {
                                    runner.run(
                                        SpikeConfig(backend, SpikeModels.forBackend(backend, modelsDir.path)),
                                        onProgress = { step -> scope.launch { log += "$backend: $step…" } },
                                        onRow = { row -> file.appendText(SpikeReport.row(row) + "\n") },
                                    )
                                }
                                log += SpikeReport.toMarkdown(header, rows)
                                log += "Saved ${file.path}"
                            } catch (e: CancellationException) {
                                throw e
                            } catch (t: Throwable) {
                                log += "Run failed: $t"
                            } finally {
                                running = false
                            }
                        }
                    },
                ) { Text("Run $backend") }
                Spacer(Modifier.width(8.dp))
            }
        }
        log.forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
    }
}
