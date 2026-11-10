package app.granger.eval

import app.granger.core.library.BookHit
import app.granger.core.library.LibrarySearch
import app.granger.core.library.SearchHit
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Plan SQ Task 1.2: a query set through the real Search, one JSON line per query, for `tools/eval/search_score.py`.
 *
 * The set is a JSON file under `files/eval/`: a list of items, or `{"items": [...]}`. Each item has an `id` and its text
 * as `query` (the known-item set, `tools/eval/known_items.py`) or `question` (Ask's datasets). Each line written holds the
 * item's id and query, then Search's groups as the screen asks for them (titles, books, full text), each hit with its
 * pack's `source` (the pack's name, as Ask's datasets name sources) and `file` (its ZIM file name), and how long each
 * group took on this phone. A run picks up where a killed one stopped: ids already written are skipped.
 */
class SearchEval(
    private val search: LibrarySearch,
    /** Pack id → (name, file name). */
    private val packs: Map<String, Pair<String, String>>,
    private val clock: () -> Long,
    /** Best matches (Plan SQ Phase 4); null leaves them out of the records. */
    private val best: (suspend (String) -> app.granger.core.research.BestMatches.Result)? = null,
) {
    var onItem: (String, Int, Int) -> Unit = { _, _, _ -> }

    suspend fun run(set: File, out: File) {
        val items = parseItems(set.readText())
        val done = if (out.exists()) out.readLines().filter { it.isNotBlank() }.map { JSONObject(it).getString("id") }.toSet() else emptySet()
        items.filter { it.first !in done }.forEachIndexed { n, (id, query) ->
            onItem(id, done.size + n + 1, items.size)
            out.appendText(record(id, query).toString() + "\n")
        }
    }

    private suspend fun record(id: String, query: String): JSONObject {
        var start = clock()
        val typed = search.titles(query)
        val titlesMs = clock() - start
        // As Search shows them once the near titles (typos) arrive.
        start = clock()
        val near = search.nearTitles(query, typed.hits)
        val nearMs = clock() - start
        val titles = typed.copy(hits = typed.withNear(near))
        start = clock()
        val books = search.books(query)
        val booksMs = clock() - start
        start = clock()
        val fullText = search.fullText(query)
        val fullTextMs = clock() - start
        start = clock()
        val matches = best?.invoke(query)
        val bestMs = clock() - start
        return JSONObject()
            .put("id", id)
            .put("query", query)
            .put("titles", JSONArray(titles.hits.map(::hit)))
            .put("titlesMore", titles.more)
            .put("books", JSONArray(books.map(::bookHit)))
            .put("fullText", JSONArray(fullText.map(::hit)))
            .put("near", near.size)
            .put("best", JSONArray(matches?.matches.orEmpty().map { m ->
                val p = m.passage
                val source = if (p.kind == app.granger.core.research.SourceKind.ARTICLE) packs[p.sourceId]?.first ?: p.sourceId else p.sourceId
                JSONObject().put("source", source).put("file", packs[p.sourceId]?.second ?: "").put("path", p.location)
                    .put("title", p.sourceTitle).put("kind", p.kind.name).put("sentence", m.sentence?.let { p.text.substring(it) } ?: "")
            }))
            .put("bestByMeaning", matches?.byMeaning ?: false)
            .put("ms", JSONObject().put("titles", titlesMs).put("near", nearMs).put("books", booksMs).put("fullText", fullTextMs).put("best", bestMs))
    }

    private fun hit(h: SearchHit): JSONObject {
        val (name, file) = packs[h.packId] ?: (h.packId to "")
        // A book in Titles has its id as packId and no path.
        return JSONObject().put("source", if (h.path.isEmpty()) h.packId else name).put("file", file).put("path", h.path).put("title", h.title)
    }

    private fun bookHit(h: BookHit): JSONObject =
        JSONObject().put("source", h.bookId).put("title", h.bookTitle).put("href", h.href).put("matches", h.matches)

    companion object {
        /** (id, text) for each item of a set: a list, or an object with `items`; text is `query`, else `question`. */
        fun parseItems(json: String): List<Pair<String, String>> {
            val trimmed = json.trimStart()
            val array = if (trimmed.startsWith("[")) JSONArray(trimmed) else JSONObject(trimmed).getJSONArray("items")
            return (0 until array.length()).map { array.getJSONObject(it) }.map { item ->
                item.getString("id") to (item.optString("query").ifBlank { item.getString("question") })
            }
        }
    }
}
