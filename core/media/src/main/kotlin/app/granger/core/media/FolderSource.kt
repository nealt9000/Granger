package app.granger.core.media

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME
import android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID
import android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED
import android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE
import android.provider.DocumentsContract.Document.COLUMN_SIZE
import android.provider.DocumentsContract.Document.MIME_TYPE_DIR
import app.granger.core.library.MediaFolder
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The Storage Access Framework, as [FolderSource] reads it. Every call only queries. */
interface DocumentTree {
    data class Document(val id: String, val name: String, val mimeType: String, val sizeBytes: Long, val modifiedMs: Long)

    /** Whether Granger still holds a persisted read grant on [treeUri]. */
    fun isGranted(treeUri: Uri): Boolean

    /** The tree's top folder; null if the provider says it no longer exists. Throws if the provider can't answer. */
    fun root(treeUri: Uri): Document?

    /** A folder's direct children. Throws if the provider can't answer. */
    fun children(treeUri: Uri, documentId: String): List<Document>
}

/**
 * Folders the user picked (Voice's model), walked recursively, each one a [parts] piece of its own. A folder whose
 * grant is gone, or whose top folder the provider says no longer exists, lists empty, so its items go MISSING. A
 * provider that fails mid-walk throws, so the scan treats that folder as unknown rather than empty.
 */
class FolderSource(
    context: Context,
    private val folders: suspend () -> List<MediaFolder>,
    private val tree: DocumentTree = SafDocumentTree(context.contentResolver),
) : AudioSource {

    override suspend fun list(): List<FoundFile> = parts().flatMap { it.list() }

    /** Any document in any picked tree; the folder list itself is unknown when it can't be read. */
    override fun owns(uri: String): Boolean = DocumentsContract.isTreeUri(Uri.parse(uri))

    override suspend fun parts(): List<AudioSource> = folders().map { Folder(it) }

    private inner class Folder(private val folder: MediaFolder) : AudioSource {
        private val treeUri = Uri.parse(folder.uri)

        override fun owns(uri: String): Boolean = sameTree(Uri.parse(uri), treeUri)

        override suspend fun list(): List<FoundFile> = withContext(Dispatchers.IO) {
            if (!tree.isGranted(treeUri)) return@withContext emptyList()
            val root = tree.root(treeUri) ?: return@withContext emptyList()
            buildList { walk(treeUri, root, "${root.name}/", folder.audiobooks, depth = 0) }
        }
    }

    private fun sameTree(a: Uri, b: Uri): Boolean = try {
        DocumentsContract.isTreeUri(a) && a.authority == b.authority &&
            DocumentsContract.getTreeDocumentId(a) == DocumentsContract.getTreeDocumentId(b)
    } catch (e: IllegalArgumentException) {
        false
    }

    private fun MutableList<FoundFile>.walk(treeUri: Uri, dir: DocumentTree.Document, path: String, audiobooks: Boolean, depth: Int) {
        if (depth > MAX_DEPTH) return
        val children = tree.children(treeUri, dir.id).filterNot { it.name.startsWith('.') }
        val cover = COVER_NAMES.firstNotNullOfOrNull { name -> children.firstOrNull { it.name.equals(name, ignoreCase = true) } }
            ?.let { DocumentsContract.buildDocumentUriUsingTree(treeUri, it.id).toString() }
        for (child in children.sortedWith(compareBy(NaturalOrder) { it.name })) {
            if (child.mimeType == MIME_TYPE_DIR) {
                walk(treeUri, child, "$path${child.name}/", audiobooks, depth + 1)
            } else if (isAudio(child)) {
                add(FoundFile(
                    uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, child.id).toString(),
                    displayName = child.name,
                    folder = path,
                    sizeBytes = child.sizeBytes,
                    modifiedMs = child.modifiedMs,
                    durationMs = 0L,
                    systemAudiobook = false,
                    inAudiobookFolder = audiobooks,
                    folderCoverUri = cover,
                ))
            }
        }
    }

    /** Providers often call an .m4b application/octet-stream, so the extension counts too. */
    private fun isAudio(d: DocumentTree.Document): Boolean =
        d.mimeType.startsWith("audio/") || d.name.substringAfterLast('.', "").lowercase() in AUDIO_EXTENSIONS

    private companion object {
        const val MAX_DEPTH = 32
        val COVER_NAMES = listOf("folder.jpg", "cover.jpg")
        val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "m4b", "aac", "flac", "ogg", "oga", "opus", "wav", "wma", "mka", "aiff", "aif", "ape", "wv")
    }
}

/** [DocumentTree] over a [ContentResolver]: persisted-grant lookups and children queries, nothing else. */
class SafDocumentTree(private val resolver: ContentResolver) : DocumentTree {
    private val columns = arrayOf(COLUMN_DOCUMENT_ID, COLUMN_DISPLAY_NAME, COLUMN_MIME_TYPE, COLUMN_SIZE, COLUMN_LAST_MODIFIED)

    override fun isGranted(treeUri: Uri): Boolean =
        resolver.persistedUriPermissions.any { it.uri == treeUri && it.isReadPermission }

    override fun root(treeUri: Uri): DocumentTree.Document? {
        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        // No provider to ask (not installed, or not answering): unknown.
        val client = resolver.acquireUnstableContentProviderClient(uri) ?: throw IOException("No provider for $treeUri")
        return client.use {
            // A DocumentsProvider answers a document that's gone (FileNotFoundException, e.g. a deleted folder or an
            // SD card taken out) with a null cursor; a provider that dies or fails otherwise throws.
            val cursor = it.query(uri, columns, null, null, null) ?: return null
            cursor.use { c -> if (c.moveToFirst()) c.document() else null }
        }
    }

    override fun children(treeUri: Uri, documentId: String): List<DocumentTree.Document> =
        query(DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId))

    private fun query(uri: Uri): List<DocumentTree.Document> {
        val cursor = resolver.query(uri, columns, null, null, null) ?: throw IOException("No cursor for $uri")
        return cursor.use { c -> buildList { while (c.moveToNext()) add(c.document()) } }
    }

    private fun Cursor.document() = DocumentTree.Document(
        id = getString(0),
        name = getString(1).orEmpty(),
        mimeType = getString(2).orEmpty(),
        sizeBytes = if (isNull(3)) 0L else getLong(3),
        modifiedMs = if (isNull(4)) 0L else getLong(4),
    )
}
