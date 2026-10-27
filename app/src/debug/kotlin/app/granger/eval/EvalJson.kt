package app.granger.eval

import org.json.JSONArray
import org.json.JSONObject

/** One question of an eval dataset: the fields the phone needs (the expected answers are only used on the desktop). */
data class EvalItem(
    val id: String,
    val group: String,
    val answerable: Boolean,
    val medical: Boolean,
    val multiStep: Boolean,
    val question: String,
    val priorQuestion: String?,
)

/** Whether to ask the model for a best guess after a declined question. */
enum class GuessModels {
    NONE, SMALL;

    companion object {
        /** "small"; "large" and "both", from before the large model was removed, ask the one model too. */
        fun parse(text: String?): GuessModels = when (text?.trim()?.lowercase()) {
            "small", "large", "both" -> SMALL
            else -> NONE
        }
    }
}

object EvalJson {
    /** The dataset array written by `tools/eval`; extra fields are ignored. */
    fun parseDataset(text: String): List<EvalItem> {
        val array = JSONArray(text)
        return List(array.length()) { i ->
            val o = array.getJSONObject(i)
            EvalItem(
                id = o.getString("id"),
                group = o.optString("group"),
                answerable = o.optBoolean("answerable"),
                medical = o.optBoolean("medical"),
                multiStep = o.optBoolean("multiStep"),
                question = o.getString("question"),
                priorQuestion = if (o.isNull("priorQuestion")) null else o.optString("priorQuestion").ifBlank { null },
            )
        }
    }

    /** Keeps the items whose id is in [ids] (if given) and whose group is in [groups] (if given). Both are comma-joined. */
    fun filter(items: List<EvalItem>, ids: String?, groups: String?): List<EvalItem> {
        fun set(s: String?) = s?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()?.takeIf { it.isNotEmpty() }
        val wantIds = set(ids)
        val wantGroups = set(groups)
        return items.filter { (wantIds == null || it.id in wantIds) && (wantGroups == null || it.group in wantGroups) }
    }
}
