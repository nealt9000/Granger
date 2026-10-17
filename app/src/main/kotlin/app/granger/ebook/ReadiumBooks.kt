package app.granger.ebook

import android.content.Context
import android.util.Log
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentFactory
import kotlinx.coroutines.CancellationException
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser
import java.io.File

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
 * blank fragment, which [NavigatorHost] replaces once the book is open. Every other class is built as usual.
 */
class ReadiumFragmentFactory : FragmentFactory() {
    override fun instantiate(classLoader: ClassLoader, className: String): Fragment {
        if (className != EpubNavigatorFragment::class.java.name) return super.instantiate(classLoader, className)
        val open = OpenBooks.current ?: return Fragment()
        val fragments = OpenBooks.navigatorFragments?.takeIf { it.first === open.second } ?: return Fragment()
        return fragments.second.instantiate(classLoader, className)
    }
}
