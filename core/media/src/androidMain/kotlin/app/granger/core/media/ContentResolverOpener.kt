package app.granger.core.media

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor

/**
 * How the scanner opens phone files: read-only, always. The phone's media is never written, renamed or deleted
 * by Granger; this is the one place a mode is chosen. [openFile] is the resolver call (a seam for tests).
 */
class ContentResolverOpener(private val openFile: (Uri, String) -> ParcelFileDescriptor?) : (String) -> ParcelFileDescriptor? {
    constructor(resolver: ContentResolver) : this({ uri, mode -> resolver.openFileDescriptor(uri, mode) })

    override fun invoke(uri: String): ParcelFileDescriptor? = openFile(Uri.parse(uri), READ_ONLY)

    private companion object {
        const val READ_ONLY = "r"
    }
}
