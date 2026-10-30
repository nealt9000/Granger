package app.granger.core.text

/**
 * Names in the order a person reads them: "page 2" before "page 10", case ignored, `\` read as `/`. Runs of digits
 * compare as numbers (leading zeros aside: "02" and "2" tie, and the rest decides); everything else compares as text.
 * Comic pages ([app.granger.core.books.ComicToPdf]) and an archive.org item's parts ([app.granger.core.archive.ArchiveParts]) are sorted with it.
 */
object NaturalOrder : Comparator<String> {
    private val CHUNK = Regex("\\d+|\\D+")

    override fun compare(a: String, b: String): Int {
        val x = chunks(a)
        val y = chunks(b)
        for (i in 0 until minOf(x.size, y.size)) {
            val p = x[i]
            val q = y[i]
            val c = if (p[0].isDigit() && q[0].isDigit()) {
                val m = p.trimStart('0')
                val n = q.trimStart('0')
                m.length.compareTo(n.length).takeIf { it != 0 } ?: m.compareTo(n)
            } else {
                p.compareTo(q)
            }
            if (c != 0) return c
        }
        return x.size.compareTo(y.size)
    }

    private fun chunks(s: String): List<String> = CHUNK.findAll(s.replace('\\', '/').lowercase()).map { it.value }.toList()
}
