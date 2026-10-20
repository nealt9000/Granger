package app.granger.media

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import java.util.Base64
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ArtworkTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var covers: File
    private lateinit var music: File
    private lateinit var audiobooks: File
    private lateinit var outside: File
    private lateinit var artwork: Artwork

    @Before fun setUp() {
        covers = tmp.newFolder("files", "media-covers")
        music = tmp.newFolder("volume", "music")
        audiobooks = tmp.newFolder("volume", "audiobooks")
        outside = tmp.newFolder("volume", "packs")
        artwork = Artwork { listOf(covers, music, audiobooks) }
    }

    private fun token(uri: Uri): String = uri.lastPathSegment!!

    private fun raw(text: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray())

    @Test fun coverUnderARootRoundTrips() {
        val scanned = File(covers, "0123abcd.jpg").apply { writeBytes(byteArrayOf(1)) }
        val downloaded = File(music, "archive-org/holst/cover.jpg").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(2)) }
        val book = File(audiobooks, "librivox/tm/cover.png").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(3)) }

        for (f in listOf(scanned, downloaded, book)) {
            val uri = artwork.uri(f.path)!!
            assertThat(uri.scheme).isEqualTo("content")
            assertThat(uri.authority).isEqualTo(Artwork.AUTHORITY)
            assertThat(uri.pathSegments).hasSize(1)
            assertThat(artwork.file(token(uri))!!.canonicalFile).isEqualTo(f.canonicalFile)
        }
        // The token is <root index>/<relative path>, URL-safe and unpadded.
        assertThat(token(artwork.uri(downloaded.path)!!)).isEqualTo(raw("1/archive-org/holst/cover.jpg"))
        assertThat(token(artwork.uri(downloaded.path)!!)).doesNotContain("=")
    }

    @Test fun pathsOutsideTheRootsHaveNoUri() {
        val pack = File(outside, "x.jpg").apply { writeBytes(byteArrayOf(1)) }
        assertThat(artwork.uri(pack.path)).isNull()
        assertThat(artwork.uri(File(covers, "missing.jpg").path)).isNull()      // not there
        assertThat(artwork.uri(covers.path)).isNull()                            // the root itself
        assertThat(artwork.file(raw("3/x.jpg"))).isNull()                        // no such root
        assertThat(artwork.file(raw("-1/x.jpg"))).isNull()
        assertThat(artwork.file(raw("0" + pack.path))).isNull()                   // an absolute path
        assertThat(artwork.file(raw("x.jpg"))).isNull()                           // no index
        assertThat(artwork.file("not base64 !")).isNull()
        assertThat(artwork.file("")).isNull()
    }

    @Test fun dotDotAndSymlinksOutAreRefused() {
        File(outside, "x.jpg").writeBytes(byteArrayOf(1))
        File(covers, "real.jpg").writeBytes(byteArrayOf(2))
        // ".." in a token, even one that comes back inside a root.
        assertThat(artwork.file(raw("1/../packs/x.jpg"))).isNull()
        assertThat(artwork.file(raw("1/a/../../packs/x.jpg"))).isNull()
        assertThat(artwork.file(raw("0/../media-covers/real.jpg"))).isNull()
        assertThat(artwork.uri(File(music, "../packs/x.jpg").path)).isNull()
        // A link inside a root that points out of it.
        val link = File(music, "link.jpg")
        Files.createSymbolicLink(link.toPath(), File(outside, "x.jpg").toPath())
        assertThat(artwork.uri(link.path)).isNull()
        assertThat(artwork.file(raw("1/link.jpg"))).isNull()
        // A linked folder too.
        Files.createSymbolicLink(File(music, "dir").toPath(), outside.toPath())
        assertThat(artwork.uri(File(music, "dir/x.jpg").path)).isNull()
        assertThat(artwork.file(raw("1/dir/x.jpg"))).isNull()
    }

    @Test fun onlyImageNamesAreServed() {
        val item = File(music, "archive-org/holst").apply { mkdirs() }
        for (name in listOf(".granger-item.json", "01 Mars.mp3", "cover.jpg.part", "notes.txt", "cover")) {
            File(item, name).writeText("x")
            assertThat(artwork.uri(File(item, name).path)).isNull()
            assertThat(artwork.file(raw("1/archive-org/holst/$name"))).isNull()
        }
        for (name in listOf("a.jpg", "b.JPEG", "c.png", "d.webp")) {
            File(item, name).writeText("x")
            assertThat(artwork.uri(File(item, name).path)).isNotNull()
        }
        File(item, "folder.jpg").mkdirs()                                        // a folder with an image name
        assertThat(artwork.uri(File(item, "folder.jpg").path)).isNull()
    }
}
