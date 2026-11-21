package app.granger.core.taglib

// Plan I6 Task 1: shared, so the tag rules (TagParser) read the same map on both phones. Android's TagLib (JNI, in
// :core:media's androidMain over :core:taglib's native library) fills it; the iPhone fills it from AVAsset metadata.

/** Tags and audio properties of one file, as TagLib reports them. */
data class RawTags(
    /** TagLib PropertyMap: upper-case keys (TITLE, ALBUMARTIST, REPLAYGAIN_TRACK_GAIN, …) to values. */
    val properties: Map<String, List<String>>,
    val durationMs: Long,
    val sampleRate: Int,
    val bitrateKbps: Int,
    val channels: Int,
    /** The first embedded picture (front cover preferred), or null. */
    val cover: ByteArray?,
) {
    override fun equals(other: Any?): Boolean =
        other is RawTags && properties == other.properties && durationMs == other.durationMs &&
            sampleRate == other.sampleRate && bitrateKbps == other.bitrateKbps && channels == other.channels &&
            (cover?.contentEquals(other.cover) ?: (other.cover == null))

    override fun hashCode(): Int =
        listOf(properties, durationMs, sampleRate, bitrateKbps, channels, cover?.contentHashCode()).hashCode()
}
