package app.granger.core.research

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** Plan R2 Task 6: a word's dictionary entry leads the passages, behind the `headwords` flag. */
class HeadwordPipelineTest {
    private val article = Passage(SourceKind.ARTICLE, "top", "Romeo and Juliet", text = "Wherefore art thou Romeo? Juliet asks, on the balcony.")
    private val entry = Passage(SourceKind.ARTICLE, "wikt", "wherefore", heading = "Adverb", text = "wherefore (interrogative) Why, for what reason.")

    private fun pipeline(headwords: Boolean, asked: MutableList<String>, prompts: MutableList<String>): ResearchPipeline {
        val finder = object : PassageFinder {
            override suspend fun find(keywords: List<String>) = listOf(article)
            override suspend fun headword(word: String, languages: List<ResearchLanguage>): List<Passage> {
                asked += word
                return listOf(entry)
            }
        }
        val generator = TextGenerator { prompt, _ ->
            if (prompt.startsWith("Turn the question")) flowOf("keywords: wherefore, juliet\nmedical: no")
            else { prompts += prompt; flowOf("Why [1].") }
        }
        val models = object : ResearchModels {
            override suspend fun generator() = generator
            override suspend fun embedder() = PassageEmbedder { texts, _ -> texts.map { floatArrayOf(1f) } }
        }
        return ResearchPipeline(finder, models, RankingConfig(), headwords = headwords)
    }

    @Test fun theEntryComesFirstWhenTheQuestionAsksAboutAWord() = runTest {
        val asked = mutableListOf<String>()
        val prompts = mutableListOf<String>()
        pipeline(headwords = true, asked, prompts).run(ResearchRequest("when juliet says wherefore art thou romeo, what does wherefore mean")).toList()
        assertThat(asked).containsExactly("wherefore")
        assertThat(prompts.single()).contains("[1] wherefore")
    }

    @Test fun offOrNotAboutAWordTheDictionaryIsNotAsked() = runTest {
        val asked = mutableListOf<String>()
        pipeline(headwords = false, asked, mutableListOf()).run(ResearchRequest("what does wherefore mean")).toList()
        pipeline(headwords = true, asked, mutableListOf()).run(ResearchRequest("who is juliet's cousin")).toList()
        assertThat(asked).isEmpty()
    }
}
