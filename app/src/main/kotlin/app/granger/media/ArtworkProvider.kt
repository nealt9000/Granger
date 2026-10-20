package app.granger.media

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.annotation.VisibleForTesting
import java.io.FileNotFoundException

/**
 * Serves cover art, read-only, at `content://app.granger.artwork/<token>` (see [Artwork]): exported with no
 * permission so Android Auto and the launcher can load the covers the session and the widget name. Covers aren't
 * secret, but nothing else is reachable: [Artwork.file] resolves only images under the media roots.
 *
 * [openFile] refuses every mode but "r"; there is no query, and insert, update and delete do nothing.
 * It builds its own [Artwork] from the context (it may be created before the app's container).
 */
class ArtworkProvider : ContentProvider() {

    @VisibleForTesting internal var artworkOverride: Artwork? = null

    private val defaultArtwork by lazy { Artwork.forContext(requireNotNull(context)) }
    private val artwork: Artwork get() = artworkOverride ?: defaultArtwork

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("Granger's artwork is read-only")
        val file = token(uri)?.let(artwork::file) ?: throw FileNotFoundException("No artwork $uri")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String? = token(uri)?.let(artwork::file)?.let { Artwork.mimeType(it.name) }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    private fun token(uri: Uri): String? =
        uri.takeIf { it.authority == Artwork.AUTHORITY }?.pathSegments?.singleOrNull()
}
