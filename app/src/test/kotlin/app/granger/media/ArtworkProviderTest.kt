package app.granger.media

import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.net.Uri
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
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
class ArtworkProviderTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var cover: File
    private lateinit var artwork: Artwork
    private lateinit var provider: ArtworkProvider
    private val uri get() = artwork.uri(cover.path)!!

    @Before fun setUp() {
        val covers = tmp.newFolder("media-covers")
        cover = File(covers, "abc.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        File(covers, "b.png").writeBytes(byteArrayOf(4))
        File(covers, "c.webp").writeBytes(byteArrayOf(5))
        artwork = Artwork({ listOf(covers) }, { ByteArray(32) { 1 } })
        val info = ProviderInfo().apply {
            authority = Artwork.AUTHORITY
            exported = true
            grantUriPermissions = false
        }
        provider = Robolectric.buildContentProvider(ArtworkProvider::class.java).create(info).get()
        provider.artworkOverride = artwork
    }

    @Test fun openFileReadsTheCover() {
        val bytes = provider.openFile(uri, "r")!!.use { FileInputStream(it.fileDescriptor).readBytes() }
        assertThat(bytes).isEqualTo(byteArrayOf(1, 2, 3))
        // A token for nothing is not found.
        val nothing = Uri.parse("content://${Artwork.AUTHORITY}/bm9wZQ")
        org.junit.Assert.assertThrows(FileNotFoundException::class.java) { provider.openFile(nothing, "r") }
        // The real cover's path without its MAC: a probe for whether a file is there learns nothing.
        val unsigned = Uri.parse("content://${Artwork.AUTHORITY}/" + uri.lastPathSegment!!.substringBefore('.'))
        org.junit.Assert.assertThrows(FileNotFoundException::class.java) { provider.openFile(unsigned, "r") }
        assertThat(provider.getType(unsigned)).isNull()
        val noToken = Uri.parse("content://${Artwork.AUTHORITY}")
        org.junit.Assert.assertThrows(FileNotFoundException::class.java) { provider.openFile(noToken, "r") }
        // The same through a ContentResolver, as Media3's bitmap loader and Auto read it.
        val resolved = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
            .contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        assertThat(resolved).isEqualTo(byteArrayOf(1, 2, 3))
    }

    @Test fun writeModesAreRefused() {
        for (mode in listOf("w", "rw", "wt", "wa", "rwt")) {
            val e = runCatching { provider.openFile(uri, mode) }.exceptionOrNull()
            assertThat(e).isInstanceOf(SecurityException::class.java)
        }
        assertThat(cover.readBytes()).isEqualTo(byteArrayOf(1, 2, 3))
    }

    @Test fun queryInsertUpdateDeleteDoNothing() {
        assertThat(provider.query(uri, null, null, null, null)).isNull()
        assertThat(provider.insert(uri, ContentValues().apply { put("x", 1) })).isNull()
        assertThat(provider.update(uri, ContentValues().apply { put("x", 1) }, null, null)).isEqualTo(0)
        assertThat(provider.delete(uri, null, null)).isEqualTo(0)
        assertThat(cover.exists()).isTrue()
        assertThat(cover.readBytes()).isEqualTo(byteArrayOf(1, 2, 3))
    }

    @Test fun getTypeIsTheImageType() {
        assertThat(provider.getType(uri)).isEqualTo("image/jpeg")
        assertThat(provider.getType(artwork.uri(File(cover.parentFile, "b.png").path)!!)).isEqualTo("image/png")
        assertThat(provider.getType(artwork.uri(File(cover.parentFile, "c.webp").path)!!)).isEqualTo("image/webp")
        assertThat(provider.getType(Uri.parse("content://${Artwork.AUTHORITY}/bm9wZQ"))).isNull()
    }
}
