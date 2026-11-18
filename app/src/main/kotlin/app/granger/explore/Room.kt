package app.granger.explore

import app.granger.core.library.Facet

/**
 * A room's own parts (Plan EX round 2, D3; a room is a lens): its one-line [description]; its [finding], drawn from its
 * dated topics; its plates through time ([story]: each era's best-known dated topic); where its topics also hang
 * ([overlaps]); and its doors, the rooms [before] and [after] it in its subject, where it stands [position] of [total].
 */
data class RoomUi(
    val description: String? = null,
    val finding: RoomFinding? = null,
    val story: List<ExploreArticle> = emptyList(),
    val overlaps: List<RoomOverlap> = emptyList(),
    val before: Facet? = null,
    val after: Facet? = null,
    val position: Int = 0,
    val total: Int = 0,
    /** The plates through time are still being read: their place is kept, so the wall under them doesn't move. */
    val storyPending: Boolean = false,
)

/**
 * What a room's dates say: its [oldest] dated topic, how many are [dated], and the century that holds the most of them
 * ([centuryStart], 1900 for the 1900s; [inCentury] of them), when there is one.
 */
data class RoomFinding(val oldest: ExploreArticle?, val dated: Int, val centuryStart: Long?, val inCentury: Int) {
    /** The share of the dated topics in the busiest century, in words; null when too few are dated or it isn't worth saying. */
    val share: Share? get() = if (dated < SAYABLE || centuryStart == null || centuryStart < 100) null else shareOf(inCentury, dated)

    companion object {
        /** Fewer dated topics than this and a share of them says nothing. */
        const val SAYABLE = 8
    }
}

/** Another room that holds some of this room's topics: [shared] of them, the best known named in [examples]. */
data class RoomOverlap(val subject: Subject, val facet: Facet, val shared: Int, val examples: List<String>)

/** A share of a whole as plain words say it: "about three in four". Each stands for the shares nearer it than its neighbours ([from] up). */
enum class Share(val from: Double) {
    NEARLY_ALL(0.88), THREE_IN_FOUR(0.71), TWO_IN_THREE(0.585), HALF(0.415), ONE_IN_THREE(0.29), ONE_IN_FOUR(0.225), ONE_IN_FIVE(0.15), ONE_IN_TEN(0.085),
}

/** [part] of [whole] in words (pure, for tests); null for a share under about one in ten, or of nothing. */
fun shareOf(part: Int, whole: Int): Share? {
    if (whole <= 0 || part <= 0) return null
    val f = part.toDouble() / whole
    return Share.entries.firstOrNull { f >= it.from }
}

/**
 * The century holding the most of [years] (pure, for tests): its first year (1900 for 1900–1999; years are astronomical,
 * so -100 is 101–2 BC) and how many fall in it. The later century wins a tie. Null with no years.
 */
fun busiestCentury(years: LongArray): Pair<Long, Int>? {
    if (years.isEmpty()) return null
    val counts = HashMap<Long, Int>()
    for (y in years) counts.merge(Math.floorDiv(y, 100L) * 100L, 1, Int::plus)
    return counts.entries.sortedWith(compareByDescending<Map.Entry<Long, Int>> { it.value }.thenByDescending { it.key }).first().let { it.key to it.value }
}

/**
 * The neighbours of the one [here] picks out of [all] (pure, for tests): the one before, its place from 1, the one after.
 * The ends meet, so a walk through the doors never stops at a wall; alone, or not found, it has no neighbours.
 */
fun <T> neighbours(all: List<T>, here: (T) -> Boolean): Triple<T?, Int, T?> {
    val at = all.indexOfFirst(here)
    if (at < 0 || all.size < 2) return Triple(null, at + 1, null)
    return Triple(all[(at - 1 + all.size) % all.size], at + 1, all[(at + 1) % all.size])
}

/** A century as Roman numerals say it in Spanish (1900 is XX); empty for a year before 1. */
fun romanCentury(centuryStart: Long): String {
    var n = (centuryStart / 100 + 1).toInt()
    if (centuryStart < 0 || n <= 0) return ""
    val out = StringBuilder()
    for ((v, r) in listOf(10 to "X", 9 to "IX", 5 to "V", 4 to "IV", 1 to "I")) while (n >= v) { out.append(r); n -= v }
    return out.toString()
}

/**
 * The rooms' one-line descriptions, from the app's own list (assets/explore/rooms.json, written from lenses.json by
 * tools/subjects/room_descriptions.py): read once, on first use.
 */
class RoomDescriptions(private val read: () -> String) {
    constructor(assets: android.content.res.AssetManager) : this({ assets.open(ASSET).bufferedReader().use { it.readText() } })

    private val all: org.json.JSONObject by lazy { runCatching { org.json.JSONObject(read()) }.getOrDefault(org.json.JSONObject()) }

    /** The room [key]'s (`subject.lens`) description in [language] ("es" for Spanish, else English); null when it has none. */
    fun of(key: String, language: String): String? =
        all.optJSONObject(key)?.let { o -> o.optString(if (language == "es") "es" else "en").ifBlank { o.optString("en") }.ifBlank { null } }

    companion object {
        const val ASSET = "explore/rooms.json"
    }
}
