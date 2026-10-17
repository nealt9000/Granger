package app.granger

import android.os.Bundle
import android.util.Log
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import app.granger.core.library.BookIds
import app.granger.ebook.ReadiumFragmentFactory
import app.granger.launch.LaunchDecider
import app.granger.launch.LaunchFacts
import app.granger.launch.StartScreen
import app.granger.launch.isBookResumable
import app.granger.launch.isPackResumable
import app.granger.theme.GrangerTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A [FragmentActivity] because the e-book reader hosts Readium's navigator fragment. */
class MainActivity : FragmentActivity() {
    private lateinit var container: AppContainer

    /** Null while the launch decision reads Room; the screen stays blank (background colour) meanwhile. */
    private val start = mutableStateOf<StartScreen?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Before super.onCreate, which restores fragments: the e-book navigator is rebuilt from the open book.
        supportFragmentManager.fragmentFactory = ReadiumFragmentFactory()
        super.onCreate(savedInstanceState)
        container = (application as GrangerApp).container
        // So rail availability reflects the files on disk now, not the last scan.
        container.appScope.launch { runCatching { container.library.refresh() } }
        container.appScope.launch {
            // Neither may crash the launch; a failed scan or index pass is retried at the next start.
            try {
                container.bookshelf.refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("Granger", "Book scan failed", e)
            }
            try {
                container.bookIndex.indexPending()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("Granger", "Book indexing failed", e)
            }
        }
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

    /** Uses the Room rows from the last scan, plus a check that the pack's or book's file still exists. */
    private suspend fun decide(clean: Boolean, booted: Boolean, firstRun: Boolean): StartScreen {
        val lastTab = container.sessions.lastTab()
        val packOk = lastTab != null && if (BookIds.isBook(lastTab.packId)) {
            isBookResumable(container.bookshelf.book(lastTab.packId))
        } else {
            container.library.packs.first().any { it.id == lastTab.packId && isPackResumable(it.status, it.filePath) }
        }
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
