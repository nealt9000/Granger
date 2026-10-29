package app.granger.core.library

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NautilusDatabaseTest {
    @Test fun eachFileTakesItsDocumentsTitle() {
        // The start of zimgit-food-preparation_en_2025-04-11.zim's database.js.
        val js = """
            var DATABASE = [
            {'_id': '00000', 'ti': 'Philmont Trail Cookbook', 'dsc': 'Eat hearty and happy camping', 'aut': 'Various', 'fp': ['Food Preparation (1).pdf']},
            {'_id': '00001', 'ti': 'Camping Recipes', 'dsc': '150 pages of Dutch Oven recipes', 'aut': 'Various', 'fp': ['Food Preparation (2).pdf']},
            ];
        """.trimIndent()
        assertThat(NautilusDatabase.parse(js)).containsExactly(
            "Food Preparation (1).pdf", "Philmont Trail Cookbook",
            "Food Preparation (2).pdf", "Camping Recipes",
        )
    }

    @Test fun escapedQuotesAndSeveralFilesAreRead() {
        val js = """var DATABASE = [{'ti': 'Grandma\'s "Best" Jams', 'dsc': 'It\'s [not] a list: {}', 'fp': ['jams 1.pdf', "jams \"2\".pdf"]},
            {"ti": "Back\\slash", "fp": ["b.pdf"]}]"""
        assertThat(NautilusDatabase.parse(js)).containsExactly(
            "jams 1.pdf", "Grandma's \"Best\" Jams",
            "jams \"2\".pdf", "Grandma's \"Best\" Jams",
            "b.pdf", "Back\\slash",
        )
    }

    @Test fun aRecordWithoutATitleOrFilesIsSkipped() {
        val js = "var DATABASE = [{'ti': '', 'fp': ['a.pdf']}, {'ti': 'No files'}, {'fp': ['c.pdf']}, {'ti': 'D', 'fp': ['d.pdf']}]"
        assertThat(NautilusDatabase.parse(js)).containsExactly("d.pdf", "D")
    }

    @Test fun textThatIsNoListGivesNothing() {
        assertThat(NautilusDatabase.parse("")).isEmpty()
        assertThat(NautilusDatabase.parse("function x() { return 'unclosed")).isEmpty()
        assertThat(NautilusDatabase.parse("<html>Not found</html>")).isEmpty()
    }
}
