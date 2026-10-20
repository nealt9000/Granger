package app.granger.media

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
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
        artwork = Artwork({ listOf(covers, music, audiobooks) }, { KEY })
    }

    private fun token(uri: Uri): String = uri.lastPathSegment!!

    private fun b64(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    /** A payload signed with [key] the way [Artwork] signs it: HMAC-SHA256, the first 16 bytes. */
    private fun signed(text: String, key: ByteArray = KEY): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
        val payload = b64(text.toByteArray())
        return "$payload.${b64(mac.doFinal(payload.toByteArray()).copyOf(16))}"
    }

    /** Hand-made tokens are signed, so these reach the path checks. */
    private fun raw(text: String) = signed(text)

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
        // The token is <root index>/<relative path> and its 16-byte MAC, both URL-safe and unpadded.
        val t = token(artwork.uri(downloaded.path)!!)
        assertThat(t).isEqualTo(signed("1/archive-org/holst/cover.jpg"))
        assertThat(t).doesNotContain("=")
        assertThat(Base64.getUrlDecoder().decode(t.substringAfter('.'))).hasLength(16)
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

    @Test fun tokensWithoutTheRightMacAreRefused() {
        File(covers, "real.jpg").writeBytes(byteArrayOf(1))
        File(covers, "other.jpg").writeBytes(byteArrayOf(2))
        val good = token(artwork.uri(File(covers, "real.jpg").path)!!)
        val payload = good.substringBefore('.')
        val mac = good.substringAfter('.')
        assertThat(artwork.file(good)).isNotNull()
        assertThat(artwork.file(payload)).isNull()                                       // no MAC
        assertThat(artwork.file("$payload.")).isNull()                                   // an empty MAC
        assertThat(artwork.file("$payload.${mac.dropLast(2)}")).isNull()                 // truncated
        assertThat(artwork.file("$payload.${b64(ByteArray(16))}")).isNull()               // wrong
        assertThat(artwork.file("$payload.$mac.$mac")).isNull()
        assertThat(artwork.file("${b64("0/other.jpg".toByteArray())}.$mac")).isNull()     // another payload, this MAC
        assertThat(artwork.file(signed("0/real.jpg", key = ByteArray(32) { 7 }))).isNull() // another install's key
        assertThat(Artwork({ listOf(covers) }, { ByteArray(32) { 7 } }).file(good)).isNull()
    }

    @Test fun theKeyPersistsAcrossInstances() {
        val keyFile = File(tmp.root, "files/artwork.key")
        val first = Artwork.loadKey(keyFile)
        assertThat(first).hasLength(32)
        assertThat(keyFile.exists()).isTrue()
        assertThat(Artwork.loadKey(keyFile)).isEqualTo(first)
        assertThat(tmp.root.listFiles { f -> f.name.contains(".tmp") }.orEmpty()).isEmpty()

        File(covers, "real.jpg").writeBytes(byteArrayOf(1))
        val minted = Artwork({ listOf(covers) }, { Artwork.loadKey(keyFile) }).uri(File(covers, "real.jpg").path)!!
        val later = Artwork({ listOf(covers) }, { Artwork.loadKey(keyFile) })
        assertThat(later.file(token(minted))!!.canonicalFile).isEqualTo(File(covers, "real.jpg").canonicalFile)
        // Another install has another key.
        val other = Artwork.loadKey(File(tmp.root, "other/artwork.key"))
        assertThat(other).isNotEqualTo(first)
    }

    private companion object {
        val KEY = ByteArray(32) { it.toByte() }
    }
}
