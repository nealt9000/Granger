package app.granger.core.library

import app.granger.core.research.SourceKind
import app.granger.core.text.Chunkers
import app.granger.core.vectors.ChunkKey
import app.granger.core.vectors.VectorFile
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DirectoryVectorSourcesTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun write(name: String) {
        VectorFile.write(File(tmp.root, name), listOf(floatArrayOf(1f, 0f, 0f, 0f)), 4, listOf("0".repeat(16)),
            listOf(ChunkKey("Knot", 0)), Chunkers.VERSION, "0".repeat(64), "0".repeat(64))
    }

    private fun index(vararg lines: String) = File(tmp.root, DirectoryVectorSources.INDEX).writeText(lines.joinToString("\n"))

    @Test fun listedFilesAreSourcesWithTheirKindAndTarget() = runTest {
        write("knots.gvec")
        write("gc-aeneid.gvec")
        index("knots.gvec\tARTICLE\twikipedia_en_knots", "gc-aeneid.gvec\tBOOK\tbook:granger:gc-aeneid")
        val sources = DirectoryVectorSources(tmp.root).current()
        assertThat(sources.map { it.kind to it.target }).containsExactly(
            SourceKind.ARTICLE to "wikipedia_en_knots", SourceKind.BOOK to "book:granger:gc-aeneid",
        ).inOrder()
        assertThat(sources.first().file.header.count).isEqualTo(1)
    }

    @Test fun missingDamagedAndMalformedEntriesAreSkipped() = runTest {
        write("knots.gvec")
        File(tmp.root, "bad.gvec").writeText("not a vector file")
        index("knots.gvec\tARTICLE\twikipedia_en_knots", "bad.gvec\tARTICLE\tx", "gone.gvec\tARTICLE\ty", "knots.gvec\tMEDIA\tz", "short line")
        assertThat(DirectoryVectorSources(tmp.root).current().map { it.target }).containsExactly("wikipedia_en_knots")
    }

    @Test fun aFileIsOpenedOnceWhileItIsUnchanged() = runTest {
        write("knots.gvec")
        index("knots.gvec\tARTICLE\twikipedia_en_knots")
        val sources = DirectoryVectorSources(tmp.root)
        assertThat(sources.current().single().file).isSameInstanceAs(sources.current().single().file)
    }

    @Test fun noIndexMeansNoSources() = runTest {
        assertThat(DirectoryVectorSources(tmp.root).current()).isEmpty()
    }
}
