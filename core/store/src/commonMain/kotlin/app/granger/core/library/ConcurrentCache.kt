package app.granger.core.library

/** A thread-safe map with `computeIfAbsent`, for the readers' caches (common code has no `ConcurrentHashMap`; Plan I3). */
class ConcurrentCache<K, V : Any> {
    private val map = HashMap<K, V>()
    private val lock = TryLock()

    fun computeIfAbsent(key: K, make: (K) -> V): V {
        lock.lock()
        try { map[key]?.let { return it } } finally { lock.unlock() }
        val made = make(key)
        lock.lock()
        try { return map.getOrPut(key) { made } } finally { lock.unlock() }
    }

    operator fun get(key: K): V? { lock.lock(); try { return map[key] } finally { lock.unlock() } }
    operator fun set(key: K, value: V) { lock.lock(); try { map[key] = value } finally { lock.unlock() } }
    fun remove(key: K): V? { lock.lock(); try { return map.remove(key) } finally { lock.unlock() } }
    fun clear() { lock.lock(); try { map.clear() } finally { lock.unlock() } }
}
