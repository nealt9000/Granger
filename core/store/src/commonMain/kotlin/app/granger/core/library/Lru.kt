package app.granger.core.library

/**
 * A map that keeps the [capacity] most recently used entries (a get counts as a use), the oldest dropped first. Common
 * Kotlin's `LinkedHashMap` has no access order, so a use re-inserts the entry. Not thread-safe: callers hold a lock.
 */
class Lru<K, V : Any>(private val capacity: Int) {
    private val map = LinkedHashMap<K, V>()

    operator fun get(key: K): V? {
        val v = map.remove(key) ?: return null
        map[key] = v
        return v
    }

    operator fun set(key: K, value: V) {
        map.remove(key)
        map[key] = value
        while (map.size > capacity) map.remove(map.keys.first())
    }

    val size: Int get() = map.size
}
