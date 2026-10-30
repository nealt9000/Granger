package app.granger

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import android.graphics.Color
import android.content.res.Configuration
import android.util.Log
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import app.granger.core.downloads.DOWNLOADS_LINK
import app.granger.core.library.BookIds
import app.granger.core.library.Bookshelf
import app.granger.ebook.ReadiumFragmentFactory
import app.granger.library.LocalPackCovers
import app.granger.ebook.dropNavigatorStandIns
import app.granger.launch.LaunchDecider
import app.granger.launch.LaunchFacts
import app.granger.launch.StartScreen
import app.granger.launch.isBookResumable
import app.granger.launch.isPackResumable
import app.granger.theme.GrangerTheme
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A [FragmentActivity] because the e-book reader hosts Readium's navigator fragment. */
class MainActivity : FragmentActivity() {
    private lateinit var container: AppContainer

    /** Counts requests to show the Player (the home-screen widget's body); each new one opens it. */
    private val openPlayer = mutableIntStateOf(0)

    /** Counts requests to show Downloads (a download notification's tap); each new one opens it. */
    private val openDownloads = mutableIntStateOf(0)

    /** Null while the launch decision reads Room; the screen stays blank (background colour) meanwhile. */
    private val start = mutableStateOf<StartScreen?>(null)

    /** The theme this activity was created in; a different choice later recreates it (Plan T1, Task 3). */
    private var createdNight = false

    override fun onCreate(savedInstanceState: Bundle?) {
        // The user's theme before the window exists, so the first frame, the bars and the reader's WebView all agree.
        createdNight = (application as GrangerApp).container.appearancePrefs.theme.value.night(phoneDark())
        setTheme(if (createdNight) R.style.Theme_Granger_Night else R.style.Theme_Granger_Field)
        (application as GrangerApp).container.readingPrefs.setAppNight(createdNight)
        // Before super.onCreate, which restores fragments: the e-book navigator is rebuilt from the open book.
        supportFragmentManager.fragmentFactory = ReadiumFragmentFactory()
        super.onCreate(savedInstanceState)
        val bars = if (createdNight) {
            SystemBarStyle.dark(Color.TRANSPARENT)
        } else {
            SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        }
        enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
        // After process death a restored navigator is a stand-in with nothing to show; drop it before onStart
        // moves it on, so nothing restored waits on a container Compose hasn't made yet.
        dropNavigatorStandIns(supportFragmentManager)
        container = (application as GrangerApp).container
        // So rail availability reflects the files on disk now, not the last scan.
        container.appScope.launch { runCatching { container.library.refresh() } }
        startBookWork(container.booksScannedThisProcess, container.appScope, container.bookshelf) { container.bookIndex.indexPending() }
        if (container.chatsSettledThisProcess.compareAndSet(false, true)) {
            // A turn still PENDING was cut off when its process died.
            container.appScope.launch { container.researchChats.interruptPending() }
            // Model roles come from the catalog; load the cached one (no network) so Research knows its models.
            container.appScope.launch { container.catalog.load() }
        }
        // Not on a re-creation: the intent that started this activity was already handled then.
        if (savedInstanceState == null && opensPlayer(intent)) openPlayer.intValue++
        if (savedInstanceState == null && opensDownloads(intent)) openDownloads.intValue++
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
            val choice by container.appearancePrefs.theme.collectAsState()
            val night = choice.night(isSystemInDarkTheme())
            // A change in Settings (or the phone's mode under "Follow the phone") repaints the window and the WebView too.
            LaunchedEffect(night) {
                container.readingPrefs.setAppNight(night)
                if (night != createdNight) recreate()
            }
            GrangerTheme(night = night) {
                val decided = start.value
                if (decided == null) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
                } else {
                    CompositionLocalProvider(LocalPackCovers provides container.gutenbergCovers::thumbnail) {
                        GrangerNavHost(
                            container, decided, openPlayerRequests = openPlayer.intValue, openDownloadsRequests = openDownloads.intValue,
                        )
                    }
                }
            }
        }
    }

    private fun phoneDark(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    /** Uses the Room rows from the last scan, plus a check that the pack's or book's file still exists. */
    private suspend fun decide(clean: Boolean, booted: Boolean, firstRun: Boolean): StartScreen {
        val lastTab = container.sessions.lastTab()
        val packOk = lastTab != null && if (BookIds.isBook(lastTab.packId)) {
            isBookResumable(container.bookshelf.book(lastTab.packId))
        } else {
            container.library.packs.first().any { it.id == lastTab.packId && isPackResumable(it.status, it.filePath) }
        }
        return LaunchDecider.decide(
            LaunchFacts(clean, booted, firstRun, lastTab, packOk, container.appearancePrefs.openTo.value, onboarded = container.onboardingPrefs.done),
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (opensPlayer(intent)) openPlayer.intValue++
        if (opensDownloads(intent)) openDownloads.intValue++
    }

    override fun onStart() {
        super.onStart()
        // Back in the foreground after a clean stop: a crash from here on must count.
        container.sessionMarker.markDirty()
        // A model release asked for mid-turn when the app was last hidden no longer applies.
        container.modelManager.appReturned()
    }

    override fun onResume() {
        super.onResume()
        // The audio permission may have been granted or revoked in system settings meanwhile: rescan if so.
        if (Features.PLAYER) container.phoneMediaScans.recheckPermission()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // The UI is hidden: free the models' memory (deferred while a reply is still being written).
        if (level >= TRIM_MEMORY_UI_HIDDEN) container.modelManager.releaseNow()
    }

    override fun onStop() {
        // Backgrounded, swiped away or reclaimed later: all normal; only a crash in the foreground skips this.
        container.sessionMarker.markCleanExit()
        super.onStop()
    }

    companion object {
        /** Boolean extra: show the full-screen Player once the app is up. */
        const val EXTRA_OPEN_PLAYER = "app.granger.extra.OPEN_PLAYER"

        /** Opens Granger at the Player: brings an existing task forward rather than stacking a second activity. */
        fun openPlayerIntent(context: Context): Intent = Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_OPEN_PLAYER, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        /** Asks for the Player, and isn't a relaunch from Recents (which replays the widget's old intent). */
        internal fun opensPlayer(intent: Intent?): Boolean = Features.PLAYER && intent != null &&
            intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0 &&
            intent.getBooleanExtra(EXTRA_OPEN_PLAYER, false)

        /** A download notification's tap ([DOWNLOADS_LINK]), and not a relaunch from Recents. */
        internal fun opensDownloads(intent: Intent?): Boolean = intent != null &&
            intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0 &&
            intent.data?.toString() == DOWNLOADS_LINK
    }
}

/**
 * Scans the books folders, then indexes what's pending, on [scope]: once per process, guarded by [startedThisProcess].
 * Rotation recreates the activity in the same process and skips it; a process restored after death runs it again.
 * Neither step may crash the launch; a failed pass is retried in the next process. Returns the job, or null when it
 * already ran.
 */
internal fun startBookWork(
    startedThisProcess: AtomicBoolean,
    scope: CoroutineScope,
    bookshelf: Bookshelf,
    indexPending: suspend () -> Unit,
): Job? {
    if (!startedThisProcess.compareAndSet(false, true)) return null
    return scope.launch {
        try {
            bookshelf.refresh()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("Granger", "Book scan failed", e)
        }
        try {
            indexPending()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("Granger", "Book indexing failed", e)
        }
    }
}
