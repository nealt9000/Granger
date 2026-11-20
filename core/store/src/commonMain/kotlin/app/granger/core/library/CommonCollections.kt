package app.granger.core.library

/** A growable set of bits (`java.util.BitSet`'s `get`/`set`), for common code (Plan I3). */
class Bits(capacity: Int = 64) {
    private var words = LongArray((capacity + 63) / 64)

    operator fun get(i: Int): Boolean = i / 64 < words.size && (words[i / 64] ushr (i % 64)) and 1L == 1L

    fun set(i: Int) {
        if (i / 64 >= words.size) words = words.copyOf(maxOf(words.size * 2, i / 64 + 1))
        words[i / 64] = words[i / 64] or (1L shl (i % 64))
    }
}

/** `Map.putIfAbsent`: puts [value] unless [key] has one; returns the old value. */
fun <K, V> MutableMap<K, V>.putIfMissing(key: K, value: V): V? {
    val old = this[key]
    if (old == null) this[key] = value
    return old
}

/** `Map.merge(key, 1, Int::plus)`: adds one to [key]'s count and returns the new count. */
internal fun <K> MutableMap<K, Int>.bump(key: K): Int = ((this[key] ?: 0) + 1).also { this[key] = it }

/** `Arrays.binarySearch` on a sorted array: the index, or `-(insertion point) - 1` when absent. */
internal fun LongArray.binarySearchCommon(key: Long): Int {
    var lo = 0
    var hi = size - 1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        val v = this[mid]
        when {
            v < key -> lo = mid + 1
            v > key -> hi = mid - 1
            else -> return mid
        }
    }
    return -(lo + 1)
}
