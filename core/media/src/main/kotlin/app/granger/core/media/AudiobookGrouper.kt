package app.granger.core.media

/** Splits audiobook files into books (spec §3.1, following Voice). */
object AudiobookGrouper {
    /**
     * A file with ≥2 chapters is one book; other audiobook files group by folder into one book, ordered by disc and
     * track tag (a missing disc counts as 1, a missing track sorts last), then natural file-name order ("2" before "10").
     * Books come out in folder order, single-file books before the folder's own book.
     */
    fun group(files: List<Pair<FoundFile, AudioTags>>, chapters: (FoundFile) -> List<Chapter>): List<List<FoundFile>> {
        val (single, rest) = files.partition { chapters(it.first).size >= 2 }
        val singles = single.map { (f, _) -> f.folder to listOf(f) }
            .sortedWith(compareBy(NaturalOrder) { it.second.single().displayName })
        val folders = rest.groupBy { it.first.folder }.map { (folder, group) ->
            folder to group.sortedWith(
                compareBy<Pair<FoundFile, AudioTags>> { it.second.disc ?: 1 }
                    .thenBy { it.second.track ?: Int.MAX_VALUE }
                    .thenBy(NaturalOrder) { it.first.displayName }
                    .thenBy { it.first.uri },
            ).map { it.first }
        }
        return (singles + folders).sortedWith(compareBy(NaturalOrder) { it.first }).map { it.second }
    }
}

/** Case-insensitive, with digit runs compared as numbers: "2.mp3" < "10.mp3". */
internal object NaturalOrder : Comparator<String> {
    private val CHUNK = Regex("""\d+|\D+""")

    override fun compare(a: String, b: String): Int {
        val x = CHUNK.findAll(a).map { it.value }.toList()
        val y = CHUNK.findAll(b).map { it.value }.toList()
        for (i in 0 until minOf(x.size, y.size)) {
            val p = x[i]
            val q = y[i]
            val c = if (p[0].isDigit() && q[0].isDigit()) {
                val pt = p.trimStart('0')
                val qt = q.trimStart('0')
                if (pt.length != qt.length) pt.length.compareTo(qt.length) else pt.compareTo(qt)
            } else {
                p.compareTo(q, ignoreCase = true)
            }
            if (c != 0) return c
        }
        return if (x.size != y.size) x.size.compareTo(y.size) else a.compareTo(b)
    }
}
