package app.granger.core.media

import app.granger.core.taglib.RawTags

/** Turns TagLib's PropertyMap into [AudioTags]. Pure: never throws, whatever the tag contents. */
object TagParser {

    /** Splits on ";" anywhere and on "/" only with spaces around it, so "AC/DC" stays one name. */
    private val SEPARATORS = Regex("""\s*;\s*|\s+/\s+""")
    private val LEADING_NUMBER = Regex("""^\s*([+-]?(?:\d+(?:[.,]\d*)?|[.,]\d+))""")

    /** R128 gains are relative to -23 LUFS; ReplayGain 2.0 is relative to -18 LUFS. */
    private const val R128_TO_REPLAYGAIN_DB = 5f

    fun parse(raw: RawTags?, fileName: String, durationMsFallback: Long): AudioTags {
        val p = raw?.properties.orEmpty()
        fun first(vararg keys: String): String =
            keys.firstNotNullOfOrNull { k -> p[k]?.firstOrNull { it.isNotBlank() }?.trim() } ?: ""
        fun multi(key: String): List<String> =
            p[key].orEmpty().flatMap { it.split(SEPARATORS) }.map { it.trim() }.filter { it.isNotEmpty() }.distinct()

        val artists = multi("ARTIST")
        val (track, trackTotalInline) = numberPair(first("TRACKNUMBER"))
        val (disc, discTotalInline) = numberPair(first("DISCNUMBER"))
        return AudioTags(
            title = first("TITLE").ifEmpty { titleFromFileName(fileName) },
            album = first("ALBUM"),
            albumArtists = multi("ALBUMARTIST").ifEmpty { artists },
            artists = artists,
            composers = multi("COMPOSER"),
            genres = multi("GENRE"),
            disc = disc,
            discTotal = discTotalInline ?: positiveInt(first("DISCTOTAL", "TOTALDISCS")),
            track = track,
            trackTotal = trackTotalInline ?: positiveInt(first("TRACKTOTAL", "TOTALTRACKS")),
            date = first("DATE"),
            originalDate = first("ORIGINALDATE"),
            releaseType = first("RELEASETYPE", "MUSICBRAINZ_ALBUMTYPE", "RELEASE TYPE")
                .split(SEPARATORS).first().trim().lowercase(),
            sort = SortTags(first("TITLESORT"), first("ALBUMSORT"), first("ARTISTSORT"), first("ALBUMARTISTSORT")),
            replayGain = replayGain { first(it) },
            durationMs = raw?.durationMs?.takeIf { it > 0 } ?: durationMsFallback,
        )
    }

    private fun replayGain(first: (String) -> String): ReplayGain? {
        fun value(key: String) = leadingFloat(first(key))
        fun r128(key: String) = first(key).toIntOrNull()?.let { it / 256f + R128_TO_REPLAYGAIN_DB }
        val rg = ReplayGain(
            trackGainDb = value("REPLAYGAIN_TRACK_GAIN") ?: r128("R128_TRACK_GAIN"),
            trackPeak = value("REPLAYGAIN_TRACK_PEAK"),
            albumGainDb = value("REPLAYGAIN_ALBUM_GAIN") ?: r128("R128_ALBUM_GAIN"),
            albumPeak = value("REPLAYGAIN_ALBUM_PEAK"),
        )
        return rg.takeUnless { it == ReplayGain(null, null, null, null) }
    }

    /** "-6.50 dB" → -6.5; "0.98" → 0.98; null if there's no leading number. */
    private fun leadingFloat(s: String): Float? =
        LEADING_NUMBER.find(s)?.groupValues?.get(1)?.replace(',', '.')?.toFloatOrNull()?.takeIf { it.isFinite() }

    /** "3/10" → (3, 10); "03" → (3, null); junk → (null, null). */
    private fun numberPair(s: String): Pair<Int?, Int?> {
        val parts = s.split('/', limit = 2)
        return positiveInt(parts[0]) to parts.getOrNull(1)?.let(::positiveInt)
    }

    private fun positiveInt(s: String): Int? = s.trim().toIntOrNull()?.takeIf { it > 0 }

    private fun titleFromFileName(fileName: String): String {
        val name = fileName.substringAfterLast('/')
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(0, dot) else name
    }
}
