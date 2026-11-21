package app.granger.core.library

import app.granger.core.zim.ZimArchive

/** A pack's place in a set (Plan WF): the set and the slice's rank, 1 the smallest. */
data class SliceKey(val setId: String, val rank: Int)

/** Which set, if any, an installed pack is a slice of; the app answers from the catalog. */
fun interface PackSlices {
    fun of(pack: Pack): SliceKey?
}

/**
 * Search and Ask don't double up (Plan WF Decision 6): while a slice and a bigger slice of its set are both installed
 * (before the smaller is removed), a hit from the smaller pack whose article the bigger one also has is left out. Article
 * paths match across a set's slices, so the check is the bigger archive resolving the same path; the bigger pack wins.
 */
object SliceDedupe {
    fun <T> apply(
        items: List<T>,
        packs: List<Pack>,
        slices: PackSlices?,
        archive: (String) -> ZimArchive?,
        packIdOf: (T) -> String?,
        pathOf: (T) -> String,
    ): List<T> {
        if (slices == null || items.isEmpty()) return items
        val keys = packs.mapNotNull { p -> slices.of(p)?.let { p.id to it } }.toMap()
        if (keys.values.groupBy { it.setId }.none { it.value.size > 1 }) return items
        return items.filterNot { item ->
            val packId = packIdOf(item) ?: return@filterNot false
            val key = keys[packId] ?: return@filterNot false
            keys.any { (other, k) ->
                k.setId == key.setId && k.rank > key.rank &&
                    runCatching { archive(other)?.resolvePath(pathOf(item)) != null }.getOrDefault(false)
            }
        }
    }
}
