package app.granger.online

import android.database.sqlite.SQLiteDatabase
import app.granger.core.catalog.PackEntry
import app.granger.core.catalog.SidecarEntry
import app.granger.core.library.BookOrigin
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Plan BK Task 3: a book pack's books read by range from a fake Kiwix mirror. The "ZIM" is a byte array with a header
 * UUID at 8..23 and two books at known offsets; download.kiwix.org redirects to the mirror, as the real one does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PackBookReadsTest {
    @get:Rule val tmp = TemporaryFolder()

    private val uuid = ByteArray(16) { (it * 7).toByte() }
    private val pdf = "%PDF-1.4 a small field manual".toByteArray()
    private val epub = byteArrayOf(0x50, 0x4B, 0x03, 0x04) + "an ebook".toByteArray()
    private val zim: ByteArray = ByteArray(8) + uuid + ByteArray(40) + pdf + ByteArray(10) + epub + ByteArray(5)
    private val pdfAt = 64L
    private val epubAt = pdfAt + pdf.size + 10
    private val zimSha = sha(zim)

    private val packUrl = "https://download.kiwix.org/zim/other/zimgit-water_en_2024-08.zim"
    private val mirror = "https://mirror.example/kiwix/zimgit-water_en_2024-08.zim"
    private val listUrl = "https://github.com/x/releases/download/bookpacks/zimgit-water_en_2024-08.gbooks"

    private val asked = mutableListOf<String>()
    private var mirrorBody: (LongRange) -> Pair<Int, ByteArray> = { r -> 206 to zim.copyOfRange(r.first.toInt(), r.last.toInt() + 1) }
    private lateinit var listBytes: ByteArray

    private fun response(chain: Interceptor.Chain, code: Int, body: ByteArray, location: String? = null) =
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).message("").code(code)
            .apply { location?.let { header("Location", it) } }
            .body(body.toResponseBody("application/octet-stream".toMediaType())).build()

    private val server = Interceptor { chain ->
        val req = chain.request()
        val url = req.url.toString()
        asked += "${req.method} $url"
        when {
            url == listUrl -> response(chain, 200, listBytes)
            url == packUrl -> response(chain, 302, ByteArray(0), mirror)
            url == mirror && req.method == "HEAD" -> response(chain, 200, ByteArray(0))
            url == mirror -> {
                val (a, b) = req.header("Range")!!.removePrefix("bytes=").split("-").map(String::toLong)
                val (code, body) = mirrorBody(a..b)
                response(chain, code, body)
            }
            else -> response(chain, 404, ByteArray(0))
        }
    }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    /** A `.gbooks` as tools/bookpacks/build.py writes it (schema 1 with Plan BK's columns), for pack sha [packSha]. */
    private fun gbooks(packSha: String = zimSha, withHashes: Boolean = true, nautilus: Boolean = true): ByteArray {
        val f = File(tmp.root, "list-${System.nanoTime()}.gbooks")
        SQLiteDatabase.openOrCreateDatabase(f, null).use { db ->
            db.execSQL("CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)")
            for ((k, v) in listOf("kind" to "gbooks", "schemaVersion" to "1", "sourceZim" to "zimgit-water_en_2024-08.zim",
                "sourceSha256" to packSha, "builder" to "tools/bookpacks/build.py", "builderVersion" to "2", "builtAt" to "2026-11-20T00:00:00Z")) {
                db.execSQL("INSERT INTO meta VALUES (?, ?)", arrayOf(k, v))
            }
            db.execSQL("CREATE TABLE collection(key TEXT PRIMARY KEY, value TEXT NOT NULL)")
            val coll = mutableListOf("title" to "Water Treatment Library", "noun" to "books", "zimName" to "zimgit-water_en",
                "zimUuid" to uuid.joinToString("") { "%02x".format(it) })
            if (nautilus) coll += "origin" to "nautilus"
            for ((k, v) in coll) db.execSQL("INSERT INTO collection VALUES (?, ?)", arrayOf(k, v))
            db.execSQL("CREATE TABLE groups(name TEXT PRIMARY KEY, ord INTEGER NOT NULL)")
            db.execSQL(
                "CREATE TABLE items(key TEXT PRIMARY KEY, path TEXT NOT NULL, format TEXT NOT NULL, title TEXT NOT NULL, " +
                    "title_source TEXT NOT NULL, author TEXT, description TEXT, licence TEXT, language TEXT, group_name TEXT NOT NULL DEFAULT '', " +
                    "ord INTEGER NOT NULL, bytes INTEGER NOT NULL, pdf_kind TEXT, part_of TEXT, work_key TEXT, work_title TEXT, " +
                    "part_no INTEGER, qid TEXT, zim_offset INTEGER, sha256 TEXT, edition TEXT)",
            )
            db.execSQL(
                "INSERT INTO items (key, path, format, title, title_source, ord, bytes, zim_offset, sha256, edition) VALUES (?,?,?,?,?,?,?,?,?,?)",
                arrayOf<Any?>("Water (1).pdf", "files/Water (1).pdf", "pdf", "Wells and Pumps", "page", 0, pdf.size, pdfAt,
                    if (withHashes) sha(pdf) else null, """{"url":"https://github.com/x/wells.epub","sha256":"ab","bytes":9,"title":"Wells (reading edition)"}"""),
            )
            db.execSQL(
                "INSERT INTO items (key, path, format, title, title_source, ord, bytes, zim_offset, sha256) VALUES (?,?,?,?,?,?,?,?,?)",
                arrayOf<Any?>("Water (2).epub", "files/Water (2).epub", "epub", "Filters", "page", 1, epub.size, epubAt, if (withHashes) sha(epub) else null),
            )
        }
        return f.readBytes().also { f.delete() }
    }

    private fun pack(list: ByteArray) = PackEntry(
        id = "zimgit-water_en", title = "Water Treatment Library", category = "other", language = "eng",
        sizeBytes = zim.size.toLong(), sha256 = zimSha, urls = listOf(packUrl), snapshotDate = "2024-08", license = "",
        hasFulltextIndex = false, zimUuid = uuid.joinToString("") { "%02x".format(it) }, zimName = "zimgit-water_en", content = "books",
        sidecars = listOf(SidecarEntry(SidecarEntry.GBOOKS, listUrl, sha(list), list.size.toLong(), 1)),
    )

    private val saved by lazy { SavedStore(tmp.newFolder("saved")) }
    private fun reads() = PackBookReads(OkHttpClient.Builder().addInterceptor(server).build(), saved, tmp.newFolder())

    @Test fun aCollectionIsItsCheckedListAndABookIsOneRangeRead() = runTest {
        listBytes = gbooks()
        val r = reads()
        val remote = r.collection(pack(listBytes), canFetch = true)!!
        assertThat(remote.collection.title).isEqualTo("Water Treatment Library")
        assertThat(remote.origin).isEqualTo(BookOrigin.NAUTILUS)
        val wells = remote.items.first()
        assertThat(remote.bookId(wells)).isEqualTo("book:nautilus:zimgit-water_en/Water (1).pdf")
        assertThat(wells.edition!!.title).isEqualTo("Wells (reading edition)")
        val got = mutableListOf<Long>()
        val file = r.book(remote, wells, canFetch = true) { done, _ -> got += done }!!
        assertThat(file.readBytes()).isEqualTo(pdf)
        assertThat(got.last()).isEqualTo(pdf.size.toLong())
        assertThat(asked.filter { it.startsWith("GET $mirror") }).hasSize(2)   // the header's UUID, then the book
        // The second book: the mirror and the UUID are remembered.
        assertThat(r.book(remote, remote.items[1], canFetch = true)!!.readBytes()).isEqualTo(epub)
        assertThat(asked.filter { it.startsWith("GET $mirror") }).hasSize(3)
        // With no signal, both come from Saved for now, and the list too, in a fresh reader.
        val again = reads().collection(pack(listBytes), canFetch = false)!!
        assertThat(reads().book(again, again.items.first(), canFetch = false)!!.readBytes()).isEqualTo(pdf)
    }

    @Test fun aListForAnotherPackFileIsRefused() = runTest {
        listBytes = gbooks(packSha = "0".repeat(64))
        assertThat(reads().collection(pack(listBytes), canFetch = true)).isNull()
    }

    @Test fun aMirrorHoldingAnotherFileGivesNothing() = runTest {
        listBytes = gbooks()
        val r = reads()
        val remote = r.collection(pack(listBytes), canFetch = true)!!
        mirrorBody = { range -> 206 to ByteArray((range.last - range.first + 1).toInt()) { 1 } }
        assertThat(r.book(remote, remote.items.first(), canFetch = true)).isNull()
        assertThat(saved.size()).isEqualTo(listBytes.size.toLong())  // only the list
    }

    @Test fun bytesThatDontMatchTheirHashAreRefusedAndWithoutHashesTheFirstBytesAreChecked() = runTest {
        listBytes = gbooks()
        val r = reads()
        val remote = r.collection(pack(listBytes), canFetch = true)!!
        val uuidRange = 8L..23L
        mirrorBody = { range -> 206 to if (range == uuidRange) uuid else ByteArray((range.last - range.first + 1).toInt()).also { "%PDF".toByteArray().copyInto(it) } }
        assertThat(r.book(remote, remote.items.first(), canFetch = true)).isNull()

        listBytes = gbooks(withHashes = false)
        val r2 = reads()
        val bare = r2.collection(pack(listBytes), canFetch = true)!!
        assertThat(r2.book(bare, bare.items.first(), canFetch = true)).isNotNull()       // starts as a PDF does
        assertThat(r2.book(bare, bare.items[1], canFetch = true)).isNull()               // an "EPUB" that isn't a zip
    }

    @Test fun aServerThatIgnoresTheRangeIsntRead() = runTest {
        listBytes = gbooks()
        val r = reads()
        val remote = r.collection(pack(listBytes), canFetch = true)!!
        mirrorBody = { _ -> 200 to zim }
        assertThat(r.book(remote, remote.items.first(), canFetch = true)).isNull()
    }

    @Test fun offlineNothingIsFetched() = runTest {
        listBytes = gbooks()
        assertThat(reads().collection(pack(listBytes), canFetch = false)).isNull()
        assertThat(asked).isEmpty()
    }
}
