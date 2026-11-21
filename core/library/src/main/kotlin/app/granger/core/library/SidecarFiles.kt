package app.granger.core.library

import java.io.File

/** Android's `File` forms of the sidecar readers' `open` (the shared ones take a path; Plan I3). */
fun QidIndex.Companion.open(file: File, packId: String, sourceSha256: String? = null, sourceName: String? = null): QidIndex? =
    if (!file.isFile) null else open(file.path, packId, sourceSha256, sourceName)

fun TimeIndex.Companion.open(file: File, packId: String): TimeIndex? = if (!file.isFile) null else open(file.path, packId)

/** Time index files pushed for development (tools/timeindex/push.py): `index.tsv` lines of `<file>\t<ZIM Name>`. */
object TimeIndexDirectory {
    /** ZIM Name to file, for files that exist; the first line for a name wins. */
    fun listed(dir: File): Map<String, File> {
        val index = File(dir, "index.tsv")
        if (!index.isFile) return emptyMap()
        val out = LinkedHashMap<String, File>()
        for (line in runCatching { index.readLines() }.getOrDefault(emptyList())) {
            val (file, name) = line.split('\t').takeIf { it.size >= 2 } ?: continue
            val f = File(dir, file)
            if (f.isFile && name !in out) out[name] = f
        }
        return out
    }
}

/** [SidecarMeta.read] for the readers still on Android's own SQLite (book links: I5 moves them). */
fun SidecarMeta.Companion.read(db: android.database.sqlite.SQLiteDatabase): SidecarMeta? = runCatching {
    val m = db.rawQuery("SELECT key, value FROM meta", null).use { c -> buildMap { while (c.moveToNext()) put(c.getString(0), c.getString(1)) } }
    SidecarMeta(m["kind"].orEmpty(), m["schemaVersion"]?.toIntOrNull() ?: 0, m["sourceSha256"].orEmpty(), m)
}.getOrNull()
