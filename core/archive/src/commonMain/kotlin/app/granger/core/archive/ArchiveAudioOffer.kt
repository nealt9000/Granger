package app.granger.core.archive

sealed interface AudioOffer {
    /** [files] are the tracks in play order; [totalBytes] is their sum (the cover is not counted). */
    data class Tracks(val files: List<ArchiveFile>, val format: String, val cover: ArchiveFile?, val totalBytes: Long) : AudioOffer
    data object LendOnly : AudioOffer
    data object Nothing : AudioOffer
}

/**
 * Decides which audio files an archive.org item offers: every usable file in the first of [FORMATS] that has any.
 * Private, LCP and checksumless files are never offered, and a restricted item is lend-only (spec §5).
 */
object ArchiveAudioOffers {
    val FORMATS: List<String> = listOf(
        "VBR MP3", "320Kbps MP3", "256Kbps MP3", "192Kbps MP3", "160Kbps MP3", "128Kbps MP3",
        "Ogg Vorbis", "Flac", "24bit Flac", "96Kbps MP3", "64Kbps MP3",
    )

    fun choose(item: ArchiveItem): AudioOffer {
        if (item.restricted) return AudioOffer.LendOnly
        val usable = item.files.filter(::isUsable)
        for (format in FORMATS) {
            val files = usable.filter { it.format == format }
            if (files.isEmpty()) continue
            val ordered = ordered(files)
            return AudioOffer.Tracks(ordered, format, cover(item), ordered.sumOf { it.sizeBytes ?: 0L })
        }
        if (item.files.any { it.isPrivate && it.format in FORMATS }) return AudioOffer.LendOnly
        return AudioOffer.Nothing
    }

    /** From search formats, before metadata: Tracks with no files when any of FORMATS is listed; LendOnly when restricted; null otherwise. */
    fun preview(doc: ArchiveDoc): AudioOffer? {
        if (doc.restricted) return AudioOffer.LendOnly
        val format = FORMATS.firstOrNull { it in doc.formats } ?: return null
        return AudioOffer.Tracks(emptyList(), format, cover = null, totalBytes = 0)
    }

    /** The largest usable "JPEG", else the "Item Tile". Waveform PNGs and spectrograms are never covers. */
    fun cover(item: ArchiveItem): ArchiveFile? {
        val usable = item.files.filter(::isUsable)
        return usable.filter { it.format == "JPEG" }.maxByOrNull { it.sizeBytes ?: 0 }
            ?: usable.firstOrNull { it.format == "Item Tile" }
    }

    /** By `track` when every file has one, else in natural name order ("2" before "10"). */
    private fun ordered(files: List<ArchiveFile>): List<ArchiveFile> {
        val byName = files.sortedWith { a, b -> compareNatural(a.name, b.name) }
        return if (files.all { it.track != null }) byName.sortedBy { it.track } else byName
    }

    private val CHUNK = Regex("\\d+|\\D+")

    internal fun compareNatural(a: String, b: String): Int {
        val x = CHUNK.findAll(a).map { it.value }.toList()
        val y = CHUNK.findAll(b).map { it.value }.toList()
        for (i in 0 until minOf(x.size, y.size)) {
            val p = x[i]
            val q = y[i]
            val c = if (p[0].isDigit() && q[0].isDigit()) {
                val m = p.trimStart('0')
                val n = q.trimStart('0')
                if (m.length != n.length) m.length - n.length else m.compareTo(n)
            } else {
                p.compareTo(q, ignoreCase = true)
            }
            if (c != 0) return c
        }
        return if (x.size != y.size) x.size - y.size else a.compareTo(b)
    }
}
