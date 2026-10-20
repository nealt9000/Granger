package app.granger.media

import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import app.granger.core.media.MediaManifest
import app.granger.core.media.MediaManifests
import app.granger.volume
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.nio.file.Files
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MediaDocumentsProviderTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var internal: File
    private lateinit var provider: MediaDocumentsProvider
    private val holst: MediaManifest = MediaFixtures.holst()
    private val book: MediaManifest = MediaFixtures.timeMachine()
    private val holstId get() = "primary:${MediaManifests.relativeDir(holst)}"
    private val holstDir get() = File(internal, MediaManifests.relativeDir(holst))

    @Before fun setUp() {
        internal = tmp.newFolder("internal")
        for (m in listOf(holst, book)) {
            MediaManifests.write(File(internal, MediaManifests.relativeDir(m)), m)
            m.files.take(3).forEach { MediaFixtures.arrive(internal, m, it) }
        }
        File(holstDir, MediaDocuments.COVER).writeBytes(byteArrayOf(1, 2, 3))
        // Everything that must never show.
        File(internal, "music/.nomedia").createNewFile()
        File(holstDir, ".cover-0123456789abcdef.jpg").writeBytes(byteArrayOf(4))
        File(holstDir, ".cover-0123456789abcdef.jpg.tmp").writeBytes(byteArrayOf(4))
        File(holstDir, ".granger-item.json.123.tmp").writeText("{")
        File(holstDir, "stray.tmp").writeText("x")
        File(holstDir, "${holst.files[5].name}.part").writeBytes(byteArrayOf(5))
        // Outside the media roots.
        File(internal, "packs").mkdirs()
        File(internal, "packs/x.zim").writeText("pack")
        File(internal, "books").mkdirs()
        File(internal, "books/x.epub").writeText("book")
        Files.createSymbolicLink(File(holstDir, "link.zim").toPath(), File(internal, "packs/x.zim").toPath())
        // A folder with no manifest isn't an item.
        File(internal, "music/archive-org/not-an-item").mkdirs()
        File(internal, "music/archive-org/not-an-item/song.mp3").writeText("x")

        val info = ProviderInfo().apply {
            authority = MediaSharing.AUTHORITY
            exported = true
            grantUriPermissions = true
            readPermission = android.Manifest.permission.MANAGE_DOCUMENTS
            writePermission = android.Manifest.permission.MANAGE_DOCUMENTS
        }
        provider = Robolectric.buildContentProvider(MediaDocumentsProvider::class.java).create(info).get()
        provider.documentsOverride = MediaDocuments(
            volumes = { listOf(volume(internal.path, "Internal storage")) },
            volumeKey = { "primary" },
            titleOf = { dir -> MediaManifests.read(dir)?.let(MediaManifests::displayTitle) },
        )
    }

    private fun Cursor.rows(): List<Map<String, Any?>> = use { c ->
        buildList {
            while (c.moveToNext()) add(c.columnNames.associateWith { col ->
                val i = c.getColumnIndex(col)
                when (c.getType(i)) {
                    Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                    Cursor.FIELD_TYPE_NULL -> null
                    else -> c.getString(i)
                }
            })
        }
    }

    private fun children(id: String) = provider.queryChildDocuments(id, null, null as String?).rows()
    private fun names(id: String) = children(id).map { it[Document.COLUMN_DISPLAY_NAME] }

    @Test fun rootListsMusicAndAudiobooks() {
        val root = provider.queryRoots(null).rows().single()
        assertThat(root[Root.COLUMN_TITLE]).isEqualTo("Granger")
        assertThat(root[Root.COLUMN_SUMMARY]).isEqualTo("Music and audiobooks")
        assertThat(root[Root.COLUMN_DOCUMENT_ID]).isEqualTo("root")
        assertThat(children("root").map { it[Document.COLUMN_DOCUMENT_ID] to it[Document.COLUMN_DISPLAY_NAME] })
            .containsExactly("music" to "Music", "audiobooks" to "Audiobooks").inOrder()
        assertThat(children("root").map { it[Document.COLUMN_MIME_TYPE] }.toSet()).containsExactly(Document.MIME_TYPE_DIR)
    }

    @Test fun itemFoldersShowTheirTitles() {
        val music = children("music")
        assertThat(music.map { it[Document.COLUMN_DOCUMENT_ID] }).containsExactly(holstId)
        assertThat(music.single()[Document.COLUMN_DISPLAY_NAME]).isEqualTo("The Planets Op. 32, H. 125")
        assertThat(names("audiobooks")).containsExactly("Time Machine")
        assertThat(provider.queryDocument(holstId, null).rows().single()[Document.COLUMN_DISPLAY_NAME])
            .isEqualTo("The Planets Op. 32, H. 125")
    }

    @Test fun itemFilesAreListedByLocalName() {
        assertThat(names(holstId)).containsExactlyElementsIn(listOf(MediaDocuments.COVER) + holst.files.take(3).map { it.name })
        val mars = children(holstId).first { it[Document.COLUMN_DISPLAY_NAME] == holst.files[0].name }
        assertThat(mars[Document.COLUMN_MIME_TYPE]).isEqualTo("audio/mpeg")
        assertThat(mars[Document.COLUMN_SIZE]).isEqualTo(holst.files[0].sizeBytes)
    }

    @Test fun openForWriteIsRefused() {
        val id = "$holstId/${MediaDocuments.COVER}"
        for (mode in listOf("w", "wa", "wt", "rw", "rwt")) {
            assertThrows(mode, SecurityException::class.java) { provider.openDocument(id, mode, null) }
        }
        assertThat(File(holstDir, MediaDocuments.COVER).readBytes()).isEqualTo(byteArrayOf(1, 2, 3))
        provider.openDocument(id, "r", null).use { pfd ->
            assertThat(FileInputStream(pfd.fileDescriptor).readBytes()).isEqualTo(byteArrayOf(1, 2, 3))
        }
    }

    @Test fun mutatingCallsAreUnsupported() {
        val file = "$holstId/${holst.files[0].name}"
        val calls = listOf<() -> Any?>(
            { provider.createDocument(holstId, "audio/mpeg", "new.mp3") },
            { provider.renameDocument(file, "renamed.mp3") },
            { provider.deleteDocument(file) },
            { provider.removeDocument(file, holstId) },
            { provider.copyDocument(file, "music") },
            { provider.moveDocument(file, holstId, "audiobooks") },
            { provider.createDocument("music", Document.MIME_TYPE_DIR, "New folder") },
            { provider.insert(DocumentsContract.buildDocumentUri(MediaSharing.AUTHORITY, file), ContentValues()) },
            { provider.update(DocumentsContract.buildDocumentUri(MediaSharing.AUTHORITY, file), ContentValues(), null, null) },
            { provider.delete(DocumentsContract.buildDocumentUri(MediaSharing.AUTHORITY, file), null, null) },
        )
        calls.forEachIndexed { i, call -> assertThrows("call $i", UnsupportedOperationException::class.java) { call() } }
        assertThat(names(holstId)).containsExactlyElementsIn(listOf(MediaDocuments.COVER) + holst.files.take(3).map { it.name })
        assertThat(File(holstDir, holst.files[0].name).length()).isEqualTo(holst.files[0].sizeBytes)
    }

    @Test fun noDocumentAdvertisesWriteOrDelete() {
        val writeFlags = Document.FLAG_SUPPORTS_WRITE or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME or
            Document.FLAG_SUPPORTS_MOVE or Document.FLAG_SUPPORTS_REMOVE or Document.FLAG_SUPPORTS_COPY or
            Document.FLAG_DIR_SUPPORTS_CREATE
        val all = mutableListOf<Map<String, Any?>>()
        all += provider.queryDocument("root", null).rows()
        fun walk(id: String) {
            for (row in children(id)) {
                all += row
                if (row[Document.COLUMN_MIME_TYPE] == Document.MIME_TYPE_DIR) walk(row[Document.COLUMN_DOCUMENT_ID] as String)
            }
        }
        walk("root")
        assertThat(all.size).isGreaterThan(10)
        for (row in all) assertThat((row[Document.COLUMN_FLAGS] as Long).toInt() and writeFlags).isEqualTo(0)
        val cover = all.single { it[Document.COLUMN_DISPLAY_NAME] == MediaDocuments.COVER }
        assertThat((cover[Document.COLUMN_FLAGS] as Long).toInt()).isEqualTo(Document.FLAG_SUPPORTS_THUMBNAIL)
        val rootFlags = (provider.queryRoots(null).rows().single()[Root.COLUMN_FLAGS] as Long).toInt()
        assertThat(rootFlags and Root.FLAG_SUPPORTS_CREATE).isEqualTo(0)
        assertThat(rootFlags).isEqualTo(Root.FLAG_LOCAL_ONLY or Root.FLAG_SUPPORTS_IS_CHILD)
    }

    @Test fun partFilesAndHiddenFilesAreNotListed() {
        val hidden = listOf(
            ".granger-item.json", ".nomedia", ".cover-0123456789abcdef.jpg", ".cover-0123456789abcdef.jpg.tmp",
            ".granger-item.json.123.tmp", "stray.tmp", "${holst.files[5].name}.part", "link.zim",
        )
        val listed = names(holstId) + names("music") + names("root")
        for (name in hidden) assertThat(listed).doesNotContain(name)
        assertThat(names("music")).doesNotContain("not-an-item")
        for (name in hidden) {
            val id = "$holstId/$name"
            assertThrows(name, FileNotFoundException::class.java) { provider.openDocument(id, "r", null) }
            assertThrows(name, FileNotFoundException::class.java) { provider.queryDocument(id, null) }
        }
        assertThrows(FileNotFoundException::class.java) { provider.queryDocument("primary:music/.nomedia", null) }
        assertThrows(FileNotFoundException::class.java) {
            provider.openDocument("primary:music/archive-org/not-an-item/song.mp3", "r", null)
        }
    }

    @Test fun pathsOutsideTheMediaRootsAreRefused() {
        val ids = listOf(
            "primary:../packs/x.zim",
            "primary:books/x.epub",
            "primary:packs/x.zim",
            "primary:music/../packs/x.zim",
            "primary:music/archive-org/../../packs/x.zim",
            "$holstId/../../../../packs/x.zim",
            "$holstId/./${MediaDocuments.COVER}",
            "$holstId//${MediaDocuments.COVER}",
            "primary:${internal.path}/packs/x.zim",
            "primary:/etc/hosts",
            "primary:music\\..\\packs\\x.zim",
            "$holstId/link.zim",
            "sd:${MediaManifests.relativeDir(holst)}/${MediaDocuments.COVER}",
            "${MediaManifests.relativeDir(holst)}/${MediaDocuments.COVER}",
            "primary:",
        )
        for (id in ids) {
            assertThrows(id, FileNotFoundException::class.java) { provider.openDocument(id, "r", null) }
            assertThrows(id, FileNotFoundException::class.java) { provider.queryDocument(id, null) }
            assertThat(provider.isChildDocument("root", id)).isFalse()
        }
        assertThat(provider.documentsOverride!!.file("primary:music/../packs/x.zim")).isNull()
    }

    @Test fun isChildFollowsTheTree() {
        val mars = "$holstId/${holst.files[0].name}"
        assertThat(provider.isChildDocument("root", mars)).isTrue()
        assertThat(provider.isChildDocument("music", mars)).isTrue()
        assertThat(provider.isChildDocument(holstId, mars)).isTrue()
        assertThat(provider.isChildDocument("audiobooks", mars)).isFalse()
        assertThat(provider.isChildDocument(mars, holstId)).isFalse()
        assertThat(provider.isChildDocument("root", "music")).isTrue()
    }

    @Test fun coverHasAThumbnail() {
        provider.openDocumentThumbnail("$holstId/${MediaDocuments.COVER}", null, null).use { afd ->
            assertThat(afd.createInputStream().readBytes()).isEqualTo(byteArrayOf(1, 2, 3))
        }
        assertThrows(FileNotFoundException::class.java) {
            provider.openDocumentThumbnail("$holstId/${holst.files[0].name}", null, null)
        }
    }
}
