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

    private fun write(name: String, chunker: Int = Chunkers.VERSION, embedder: String = "0".repeat(64)): File =
        File(tmp.root, name).also {
            VectorFile.write(it, listOf(floatArrayOf(1f, 0f, 0f, 0f)), 4, listOf("0".repeat(16)),
                listOf(ChunkKey("Knot", 0)), chunker, embedder, "0".repeat(64))
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

    @Test fun listedFilesSkipOtherChunkersAndEmbedders() = runTest {
        val ok = write("med.gvec")
        val oldChunker = write("old.gvec", chunker = Chunkers.VERSION + 1)
        val otherEmbedder = write("other.gvec", embedder = "1".repeat(64))
        val listing = listOf(
            ListedVectors(ok, SourceKind.ARTICLE, "wikipedia_en_medicine"),
            ListedVectors(oldChunker, SourceKind.ARTICLE, "old"),
            ListedVectors(otherEmbedder, SourceKind.BOOK, "book:granger:gc-emma"),
            ListedVectors(File(tmp.root, "gone.gvec"), SourceKind.ARTICLE, "gone"),
        )
        assertThat(ListedVectorSources({ listing }, embedder = { "0".repeat(64) }).current().map { it.target })
            .containsExactly("wikipedia_en_medicine")
        // Without a known embedder, any embedder's files are used.
        assertThat(ListedVectorSources({ listing }).current().map { it.target })
            .containsExactly("wikipedia_en_medicine", "book:granger:gc-emma").inOrder()
    }

    @Test fun combinedSourcesKeepTheFirstFileForATarget() = runTest {
        val downloaded = write("med-catalog.gvec")
        write("med-pushed.gvec")
        write("knots.gvec")
        index("med-pushed.gvec\tARTICLE\twikipedia_en_medicine", "knots.gvec\tARTICLE\twikipedia_en_knots")
        val catalog = ListedVectorSources({ listOf(ListedVectors(downloaded, SourceKind.ARTICLE, "wikipedia_en_medicine")) })
        val sources = CombinedVectorSources(catalog, DirectoryVectorSources(tmp.root)).current()
        assertThat(sources.map { it.target to it.file.file.name })
            .containsExactly("wikipedia_en_medicine" to "med-catalog.gvec", "wikipedia_en_knots" to "knots.gvec").inOrder()
    }
}
