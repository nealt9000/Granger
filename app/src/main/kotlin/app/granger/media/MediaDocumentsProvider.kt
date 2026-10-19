package app.granger.media

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Point
import android.net.Uri
import android.os.CancellationSignal
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import androidx.annotation.VisibleForTesting
import app.granger.R
import app.granger.core.downloads.Volume
import app.granger.core.media.MediaManifests
import java.io.File
import java.io.FileNotFoundException

/**
 * "Show in other apps" (spec §4): Granger's downloaded music and audiobooks in the Files app and other apps' file
 * pickers, **read-only**. One root, "Granger", with Music and Audiobooks; item folders by their display title.
 *
 * - Disabled in the manifest; [MediaSharing.setEnabled] turns it on from Settings, so with the switch off no other app
 *   can see or query it.
 * - No document or root advertises create, write, delete, rename, copy, move or remove. [openDocument] refuses every
 *   mode but "r", and every mutating method keeps the base class's [UnsupportedOperationException].
 * - It builds its own view of the volumes from the context (it may be created before the app's container).
 */
class MediaDocumentsProvider : DocumentsProvider() {

    @VisibleForTesting internal var documentsOverride: MediaDocuments? = null

    private val defaultDocuments by lazy { MediaSharing.documents(requireNotNull(context)) }
    private val documents: MediaDocuments get() = documentsOverride ?: defaultDocuments

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: ROOT_COLUMNS).apply {
            newRow()
                .add(Root.COLUMN_ROOT_ID, ROOT_ID)
                .add(Root.COLUMN_DOCUMENT_ID, MediaDocuments.ROOT)
                .add(Root.COLUMN_TITLE, "Granger")
                .add(Root.COLUMN_SUMMARY, "Music and audiobooks")
                .add(Root.COLUMN_FLAGS, Root.FLAG_LOCAL_ONLY or Root.FLAG_SUPPORTS_IS_CHILD)
                .add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
                .add(Root.COLUMN_MIME_TYPES, "audio/*\nimage/*")
            setNotificationUri(context?.contentResolver, DocumentsContract.buildRootsUri(MediaSharing.AUTHORITY))
        }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val doc = documents.doc(documentId) ?: throw FileNotFoundException("No document $documentId")
        return MatrixCursor(projection ?: DOCUMENT_COLUMNS).apply { add(doc) }
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        val parent = documents.doc(parentDocumentId) ?: throw FileNotFoundException("No document $parentDocumentId")
        if (!parent.isDir) throw FileNotFoundException("$parentDocumentId is not a folder")
        return MatrixCursor(projection ?: DOCUMENT_COLUMNS).apply {
            documents.children(parentDocumentId).forEach { add(it) }
            setNotificationUri(
                context?.contentResolver,
                DocumentsContract.buildChildDocumentsUri(MediaSharing.AUTHORITY, parentDocumentId),
            )
        }
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean =
        documents.isChild(parentDocumentId, documentId)

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("Granger's media are read-only")
        val file = documents.file(documentId) ?: throw FileNotFoundException("No file $documentId")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun openDocumentThumbnail(documentId: String, sizeHint: Point?, signal: CancellationSignal?): AssetFileDescriptor {
        val file = documents.file(documentId)?.takeIf { it.name == MediaDocuments.COVER }
            ?: throw FileNotFoundException("No thumbnail for $documentId")
        return AssetFileDescriptor(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY), 0, AssetFileDescriptor.UNKNOWN_LENGTH)
    }

    private fun MatrixCursor.add(doc: MediaDocuments.Doc) {
        val flags = if (!doc.isDir && doc.displayName == MediaDocuments.COVER) Document.FLAG_SUPPORTS_THUMBNAIL else 0
        newRow()
            .add(Document.COLUMN_DOCUMENT_ID, doc.id)
            .add(Document.COLUMN_DISPLAY_NAME, doc.displayName)
            .add(Document.COLUMN_MIME_TYPE, doc.mimeType)
            .add(Document.COLUMN_SIZE, doc.sizeBytes)
            .add(Document.COLUMN_LAST_MODIFIED, doc.lastModified)
            .add(Document.COLUMN_FLAGS, flags)
    }

    companion object {
        const val ROOT_ID = "granger"
        private val ROOT_COLUMNS = arrayOf(
            Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_SUMMARY,
            Root.COLUMN_FLAGS, Root.COLUMN_ICON, Root.COLUMN_MIME_TYPES,
        )
        private val DOCUMENT_COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS,
        )
    }
}

/** Turns [MediaDocumentsProvider] on and off, and tells the Files app when what it shows has changed. */
object MediaSharing {
    const val AUTHORITY = "app.granger.media.documents"

    private fun component(context: Context) = ComponentName(context, MediaDocumentsProvider::class.java)

    /** Enables or disables the provider (DONT_KILL_APP), then announces the roots changed. */
    fun setEnabled(context: Context, on: Boolean) {
        context.packageManager.setComponentEnabledSetting(
            component(context),
            if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
        notifyChanged(context)
    }

    /** The manifest disables it, so DEFAULT means off. */
    fun isEnabled(context: Context): Boolean =
        context.packageManager.getComponentEnabledSetting(component(context)) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    /** The roots and every listing (a change to the authority reaches every child-documents URI under it). Never throws. */
    fun notifyChanged(context: Context) {
        runCatching {
            val resolver = context.contentResolver
            resolver.notifyChange(DocumentsContract.buildRootsUri(AUTHORITY), null)
            for (id in listOf(MediaDocuments.ROOT, MediaDocuments.MUSIC, MediaDocuments.AUDIOBOOKS)) {
                resolver.notifyChange(DocumentsContract.buildChildDocumentsUri(AUTHORITY, id), null)
            }
            resolver.notifyChange(Uri.Builder().scheme("content").authority(AUTHORITY).build(), null)
        }
    }

    /** The documents over this phone's mounted volumes, item folders titled by their manifests. */
    fun documents(context: Context): MediaDocuments {
        val storage = context.getSystemService(StorageManager::class.java)
        return MediaDocuments(
            volumes = {
                context.getExternalFilesDirs(null).filterNotNull()
                    .filter { runCatching { Environment.getExternalStorageState(it) == Environment.MEDIA_MOUNTED }.getOrDefault(false) }
                    .map { Volume(it, label = "", removable = false, freeBytes = 0, totalBytes = 0, grangerBytes = 0) }
            },
            volumeKey = { root -> volumeKey(storage, root) },
            titleOf = { dir -> MediaManifests.read(dir)?.let(MediaManifests::displayTitle) },
        )
    }

    /** The storage volume's UUID, or "primary" for internal storage. */
    private fun volumeKey(storage: StorageManager?, root: File): String {
        val volume = runCatching { storage?.getStorageVolume(root) }.getOrNull()
        return when {
            volume == null || volume.isPrimary -> "primary"
            else -> volume.uuid ?: "v${root.path.hashCode().toUInt()}"
        }
    }
}
