package app.granger.ebook

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.ui.graphics.toArgb
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentFactory
import androidx.fragment.app.FragmentManager
import app.granger.theme.GrangerColors
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.css.Color as CssColor
import org.readium.r2.navigator.epub.css.RsProperties
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.search.SearchIterator
import org.readium.r2.shared.publication.services.search.isSearchable
import org.readium.r2.shared.publication.services.search.search
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser

/**
 * The navigator's configuration. ReadiumCSS night mode paints links #63caff, which beats the theme's text colour;
 * links take the app's accent instead, in every theme, visited or not.
 */
fun readerConfiguration(): EpubNavigatorFragment.Configuration {
    val accent = CssColor.Int(GrangerColors.Flame.toArgb())
    return EpubNavigatorFragment.Configuration(
        readiumCssRsProperties = RsProperties(linkColor = accent, visitedColor = accent),
        selectionActionModeCallback = SelectionMenu(),
    )
}

/** Finds a phrase in the book's text. */
interface BookSearcher {
    /** Up to [max] places where [query] occurs, in book order; empty when the book can't be searched. */
    suspend fun search(publication: Publication, query: String, max: Int = 200): List<Locator>
}

/** Readium's search service, read page by page until it runs dry or [max] hits are in. */
@OptIn(ExperimentalReadiumApi::class)
class ReadiumBookSearcher : BookSearcher {
    override suspend fun search(publication: Publication, query: String, max: Int): List<Locator> {
        if (!publication.isSearchable) return emptyList()
        return withContext(Dispatchers.IO) {
            val iterator = publication.search(query) ?: return@withContext emptyList()
            drain(iterator, max)
        }
    }

    /** Reads [iterator] page by page until it runs dry or [max] hits are in, and always closes it. */
    internal suspend fun drain(iterator: SearchIterator, max: Int): List<Locator> {
        try {
            val found = mutableListOf<Locator>()
            while (found.size < max) {
                currentCoroutineContext().ensureActive()
                val page = iterator.next().getOrNull() ?: break
                found += page.locators
            }
            return found.take(max)
        } finally {
            iterator.close()
        }
    }
}

/** Opens a book file for reading. */
interface BookOpener {
    /** The opened publication, which the caller closes; null when the file can't be read as a book. */
    suspend fun open(file: File): Publication?
}

/**
 * Readium's streamer over a local file. The HTTP client is only a constructor argument: nothing is fetched,
 * because every book is a file on the phone. PDFs are not supported ([DefaultPublicationParser]'s `pdfFactory` is null).
 */
class ReadiumBookOpener(context: Context) : BookOpener {
    private val httpClient = DefaultHttpClient()
    private val assetRetriever = AssetRetriever(context.contentResolver, httpClient)
    private val publicationOpener = PublicationOpener(
        DefaultPublicationParser(context.applicationContext, httpClient, assetRetriever, pdfFactory = null),
    )

    override suspend fun open(file: File): Publication? {
        return try {
            val asset = assetRetriever.retrieve(file).getOrElse {
                Log.w(TAG, "Can't read ${file.name}: ${it.message}")
                return null
            }
            publicationOpener.open(asset, allowUserInteraction = false).getOrElse {
                Log.w(TAG, "Can't open ${file.name}: ${it.message}")
                asset.close()
                return null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Opening ${file.name} failed", e)
            null
        }
    }

    private companion object {
        const val TAG = "Granger"
    }
}

/**
 * The book whose navigator may be on screen, process-wide, so [ReadiumFragmentFactory] can rebuild the navigator
 * fragment the system restores after rotation. The view model sets [current] once the book is open and clears it
 * when it is cleared; [NavigatorHost] keeps [navigatorFragments] up to date with the latest place and settings.
 */
object OpenBooks {
    /** The book id and its navigator factory. Null after process death, until the reader opens the book again. */
    @Volatile var current: Pair<String, EpubNavigatorFactory>? = null

    /** What builds the navigator fragment for the factory it is paired with. */
    @Volatile internal var navigatorFragments: Pair<EpubNavigatorFactory, FragmentFactory>? = null

    /** Forgets [factory]'s book, unless another book (or another reader of the same book) has replaced it. */
    fun release(factory: EpubNavigatorFactory) {
        if (current?.second === factory) current = null
        if (navigatorFragments?.first === factory) navigatorFragments = null
    }
}

/**
 * Installed on the activity's fragment manager before `super.onCreate`, so a restored [EpubNavigatorFragment]
 * is built from the open book. With nothing open (the process died and the book isn't reopened yet) it returns a
 * [NavigatorStandIn], which the activity drops right after `super.onCreate` ([dropNavigatorStandIns]); should one
 * survive, [NavigatorHost] replaces it once the book is open. Every other class is built as usual.
 */
class ReadiumFragmentFactory(
    /** The class built from the open book; a parameter only so tests can stand a plain fragment in for it. */
    private val navigatorClass: String = EpubNavigatorFragment::class.java.name,
) : FragmentFactory() {
    override fun instantiate(classLoader: ClassLoader, className: String): Fragment {
        if (className != navigatorClass) return super.instantiate(classLoader, className)
        val open = OpenBooks.current ?: return NavigatorStandIn()
        val fragments = OpenBooks.navigatorFragments?.takeIf { it.first === open.second } ?: return NavigatorStandIn()
        return fragments.second.instantiate(classLoader, className)
    }
}

/**
 * What a navigator restored after process death becomes. The navigator's saved state still holds its page
 * fragments, and the fragment manager restores them as this fragment's children and moves them on with it. A child
 * looks up its container in its parent's view, and a parent with no view throws ("does not have a view") from the
 * activity's `onStart`. So this fragment has a view, and drops the restored children as soon as they're created:
 * they belong to a navigator that no longer exists.
 */
class NavigatorStandIn : Fragment() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState) // Restores the children, at CREATED.
        val children = childFragmentManager.fragments
        if (children.isNotEmpty()) {
            childFragmentManager.beginTransaction().apply { children.forEach(::remove) }.commitNowAllowingStateLoss()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        FrameLayout(inflater.context)
}

/**
 * Removes the stand-ins the fragment manager restored, before anything waits on their container: called right after
 * `super.onCreate`, while the fragments are only CREATED. A real navigator (rotation, book still open) is kept for
 * [NavigatorHost] to adopt. Returns how many were removed.
 */
fun dropNavigatorStandIns(fragmentManager: FragmentManager): Int {
    val strays = fragmentManager.fragments.filterIsInstance<NavigatorStandIn>()
    if (strays.isEmpty() || fragmentManager.isDestroyed) return 0
    fragmentManager.beginTransaction().apply { strays.forEach(::remove) }.commitNowAllowingStateLoss()
    return strays.size
}
