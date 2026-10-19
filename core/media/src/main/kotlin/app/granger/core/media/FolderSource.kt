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

    /** The tree's top folder. Throws if the provider can't answer. */
    fun root(treeUri: Uri): Document

    /** A folder's direct children. Throws if the provider can't answer. */
    fun children(treeUri: Uri, documentId: String): List<Document>
}

/**
 * Folders the user picked (Voice's model), walked recursively. A folder whose grant is gone contributes nothing, so
 * only its items go MISSING; a provider that fails mid-walk throws, so the scan doesn't mistake it for an empty folder.
 */
class FolderSource(
    context: Context,
    private val folders: suspend () -> List<MediaFolder>,
    private val tree: DocumentTree = SafDocumentTree(context.contentResolver),
) : AudioSource {

    override suspend fun list(): List<FoundFile> {
        val picked = folders()
        return withContext(Dispatchers.IO) {
            picked.flatMap { folder ->
                val treeUri = Uri.parse(folder.uri)
                if (!tree.isGranted(treeUri)) return@flatMap emptyList()
                val root = tree.root(treeUri)
                buildList { walk(treeUri, root, "${root.name}/", folder.audiobooks, depth = 0) }
            }
        }
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

    override fun root(treeUri: Uri): DocumentTree.Document {
        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        return query(uri).firstOrNull() ?: throw IOException("No root document for $treeUri")
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
