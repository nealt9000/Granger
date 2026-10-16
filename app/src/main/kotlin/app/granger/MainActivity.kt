package app.granger

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import app.granger.core.library.PackStatus
import app.granger.launch.LaunchDecider
import app.granger.launch.LaunchFacts
import app.granger.launch.StartScreen
import app.granger.theme.GrangerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private lateinit var container: AppContainer

    /** Null while the launch decision reads Room; the screen stays blank (background colour) meanwhile. */
    private val start = mutableStateOf<StartScreen?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        container = (application as GrangerApp).container
        if (savedInstanceState != null) {
            // Rotation, or the OS restoring a reclaimed app: the navigation back stack restores itself.
            start.value = StartScreen.Library()
        } else {
            // SharedPreferences only, on purpose before anything else: the dirty mark must be on disk first.
            val (clean, booted, firstRun) = container.sessionMarker.readAndStartSession()
            lifecycleScope.launch {
                start.value = withContext(Dispatchers.IO) {
                    runCatching { decide(clean, booted, firstRun) }
                        .onFailure { Log.e("Granger", "Launch decision failed; opening the Library", it) }
                        .getOrDefault(StartScreen.Library())
                }
            }
        }
        setContent {
            GrangerTheme {
                val decided = start.value
                if (decided == null) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
                } else {
                    GrangerNavHost(container, decided)
                }
            }
        }
    }

    /** Uses the Room rows from the last scan; `library.refresh()` may not have run yet this launch. */
    private suspend fun decide(clean: Boolean, booted: Boolean, firstRun: Boolean): StartScreen {
        val lastTab = container.sessions.lastTab()
        val packOk = lastTab != null &&
            container.library.packs.first().any { it.id == lastTab.packId && it.status == PackStatus.OK }
        return LaunchDecider.decide(LaunchFacts(clean, booted, firstRun, lastTab, packOk))
    }

    override fun onStart() {
        super.onStart()
        // Back in the foreground after a clean stop: a crash from here on must count.
        container.sessionMarker.markDirty()
    }

    override fun onStop() {
        // Backgrounded, swiped away or reclaimed later: all normal; only a crash in the foreground skips this.
        container.sessionMarker.markCleanExit()
        super.onStop()
    }
}
