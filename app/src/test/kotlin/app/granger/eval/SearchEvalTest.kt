package app.granger.eval

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SearchEvalTest {
    @Test fun readsKnownItemsAndAskDatasets() {
        // The known-item set: an object with items, each with a query.
        assertThat(SearchEval.parseItems("""{"seed": 11, "items": [{"id": "ki-en-001", "query": "Photosynthesis", "kind": "exact"}]}"""))
            .containsExactly("ki-en-001" to "Photosynthesis")
        // Ask's datasets: a list, each with a question.
        assertThat(SearchEval.parseItems("""[{"id": "d-1", "question": "who wrote hamlet", "answers": []}]"""))
            .containsExactly("d-1" to "who wrote hamlet")
    }
}
