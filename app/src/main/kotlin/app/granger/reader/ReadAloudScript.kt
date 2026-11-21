package app.granger.reader

import org.json.JSONObject
import org.json.JSONTokener

/** A block the page named: its id (the element's own, or `g<n>`), the start of its text, and a character offset into it when a selection chose it. */
data class BlockRef(val id: String, val prefix: String, val offset: Int? = null) {
    companion object {
        /** From the page script's JSON (`{"id","prefix","offset"}`), or null for `null`, an empty answer or junk. */
        fun parse(json: String?): BlockRef? {
            if (json.isNullOrBlank() || json == "null") return null
            return runCatching {
                var value = JSONTokener(json).nextValue()
                if (value is String) value = JSONTokener(value).nextValue()     // evaluateJavascript double-encodes strings
                val o = value as? JSONObject ?: return null
                val id = o.optString("id").takeIf { it.isNotEmpty() } ?: return null
                BlockRef(id, o.optString("prefix"), if (o.has("offset") && !o.isNull("offset")) o.optInt("offset") else null)
            }.getOrNull()
        }
    }
}

/**
 * The read-aloud side of the page, run after every page loads: `window.__grangerRead`. It applies [ArticleText]'s
 * rules in the browser's DOM so block ids agree with the Kotlin side (`g<n>` counts every block element in document
 * order, noise or not), marks the sentence being read with the CSS Custom Highlight API (a class on the block where
 * the WebView lacks it), scrolls it into view while the reader follows, and reports every other scroll through the
 * bridge, with its distance and whether a finger moved it: Follow switches off, and the hand's scrolls move the chrome.
 *
 * - `firstVisible()`: the first readable block on screen below the top bar, as `{id, prefix}`.
 * - `selectionBlock()`: the block the selection starts in and the folded offset into it, `{id, prefix, offset}`.
 * - `mark(id, prefix, start, end, scroll)`: highlights characters `start..end` of the block's folded text.
 * - `clear()`.
 * - `cite(id, prefix, start, end)`: marks a cited quote ([ArticleQuote]) as the e-book marks a search hit, under its
 *   own highlight so the voice's mark doesn't clear it, and scrolls it a third of the way down. `clearCite()`.
 * - `jump(id, prefix)`: the Contents' jump to a section (Plan U3c): opens the collapsed sections around its heading and
 *   scrolls the heading to the top. It counts as the user's own scroll, so the page stops following the voice.
 */
object ReadAloudScript {
    /** The page script, shared with the iPhone since Plan I5 Task 8 ([app.granger.core.online.ReadAloudPage]). */
    val SOURCE: String get() = app.granger.core.online.ReadAloudPage.SOURCE

    /** A call into the page: `JSON.stringify(window.__grangerRead && window.__grangerRead.<fn>(args))`. */
    fun call(fn: String, vararg args: Any?): String =
        "JSON.stringify(window.__grangerRead && window.__grangerRead.$fn(" + args.joinToString(", ") { js(it) } + "))"

    private fun js(value: Any?): String = when (value) {
        null -> "null"
        is String -> JSONObject.quote(value)
        is Boolean, is Number -> value.toString()
        else -> JSONObject.quote(value.toString())
    }
}
