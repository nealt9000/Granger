package app.granger.core.books

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComicToPdfTest {
    @get:Rule val tmp = TemporaryFolder()

    @Before fun setUp() = PdfFixtures.init(ApplicationProvider.getApplicationContext())

    private fun picture(width: Int, height: Int, format: Bitmap.CompressFormat): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        return ByteArrayOutputStream().also { bitmap.compress(format, 90, it) }.toByteArray()
    }

    private fun cbz(name: String, entries: List<Pair<String, ByteArray>>): File =
        File(tmp.root, name).also { file ->
            ZipOutputStream(file.outputStream()).use { zip ->
                for ((entry, bytes) in entries) {
                    zip.putNextEntry(ZipEntry(entry))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
        }

    @Test fun everyPictureBecomesAPageInPageOrder() {
        // Named out of order, with a PNG among the JPEGs and macOS's resource-fork litter beside them.
        val archive = cbz(
            "issue.cbr",   // a zip under a .cbr name: the bytes decide
            listOf(
                "Issue 1/p10.jpg" to picture(30, 40, Bitmap.CompressFormat.JPEG),
                "Issue 1/p2.png" to picture(20, 40, Bitmap.CompressFormat.PNG),
                "Issue 1/p1.jpg" to picture(10, 40, Bitmap.CompressFormat.JPEG),
                "__MACOSX/Issue 1/._p1.jpg" to byteArrayOf(0, 1, 2),
                "Issue 1/notes.txt" to "scanned by".toByteArray(),
            ),
        )
        val out = File(tmp.root, "issue.pdf")
        assertThat(ComicToPdf.convert(archive, out)).isTrue()

        val meta = (PdfInspector.inspect(out) as Inspection.Ok).meta
        assertThat(meta.pageCount).isEqualTo(3)
        loadPdf(out).use { doc ->
            assertThat(doc.pages.map { it.mediaBox.width.toInt() }).containsExactly(10, 20, 30).inOrder()
        }
        assertThat(tmp.root.listFiles()!!.filter { it.name.startsWith(".incoming-") }).isEmpty()
    }

    @Test fun anArchiveWithoutPicturesOrThatIsntOneWritesNothing() {
        val empty = cbz("empty.cbz", listOf("readme.txt" to "nothing here".toByteArray()))
        val out = File(tmp.root, "empty.pdf")
        assertThat(ComicToPdf.convert(empty, out)).isFalse()
        assertThat(out.exists()).isFalse()

        val junk = File(tmp.root, "junk.cbr").apply { writeText("not an archive at all") }
        assertThat(ComicToPdf.convert(junk, File(tmp.root, "junk.pdf"))).isFalse()
        assertThat(File(tmp.root, "junk.pdf").exists()).isFalse()
        assertThat(tmp.root.listFiles()!!.filter { it.name.startsWith(".incoming-") }).isEmpty()
    }

    @Test fun pagesSortByTheirNumbers() {
        val names = listOf("p10.jpg", "P2.jpg", "p1.jpg", "cover.jpg", "p02a.jpg")
        assertThat(names.sortedWith(ComicToPdf.PAGE_ORDER)).containsExactly("cover.jpg", "p1.jpg", "P2.jpg", "p02a.jpg", "p10.jpg").inOrder()
    }
}
