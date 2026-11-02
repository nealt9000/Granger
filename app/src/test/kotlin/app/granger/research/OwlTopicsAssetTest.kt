package app.granger.research

import app.granger.core.catalog.CatalogParser
import app.granger.core.catalog.ModelRole
import app.granger.core.catalog.ParseResult
import app.granger.core.research.ReactionTopic
import app.granger.core.research.TopicCentroids
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

/** The owl's topic asset parses, covers the embedded topics, and was built from the catalog's own embedder. */
class OwlTopicsAssetTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "catalog/catalog.json").isFile }

    private val parsed = TopicCentroids.parse(File(root, "app/src/main/assets/owl/topics.txt").readText())

    @Test fun theAssetMatchesThePublishedEmbedder() {
        val key = java.util.Base64.getDecoder().decode(File(root, "catalog/public-key.txt").readText().trim())
        val catalog = (CatalogParser.parse(
            File(root, "catalog/catalog.json").readBytes(), File(root, "catalog/catalog.json.sig").readBytes(), key,
        ) as ParseResult.Ok).catalog
        // A new embedder needs the topics rebuilt (tools/owl/topics.py); until then the app uses the default lines.
        assertThat(parsed.embedderSha).isEqualTo(catalog.models.single { it.role == ModelRole.EMBEDDER }.sha256)
    }

    @Test fun aCentroidClassifiesAsItsOwnTopic() {
        val lines = File(root, "app/src/main/assets/owl/topics.txt").readLines().filter { it.isNotBlank() && !it.startsWith("#") }
        val topics = lines.map { it.substringBefore(' ') }.filter { name -> ReactionTopic.entries.any { it.name == name } }
        assertThat(topics).containsExactly("HISTORY", "SCIENCE", "LITERATURE", "SCRIPTURE", "WORDS", "PLACES", "MATHS", "HOW_TO", "MEDICAL")
        for (line in lines.filter { it.substringBefore(' ') in topics }) {
            val name = line.substringBefore(' ')
            val v = line.substringAfter(' ').split(' ').map { it.toFloat() }.toFloatArray()
            assertThat(parsed.centroids.classify(v).name).isEqualTo(name)
        }
    }
}
