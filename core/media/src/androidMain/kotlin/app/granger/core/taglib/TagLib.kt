package app.granger.core.taglib

/** Reads audio tags with TagLib (native, read-only). */
object TagLib {
    init {
        System.loadLibrary("granger_taglib")
    }

    /** Reads [fd] (opened read-only by the caller; not closed here). Null if TagLib can't parse it. */
    fun read(fd: Int, fileNameHint: String): RawTags? {
        val raw = nativeRead(fd, fileNameHint) ?: return null
        @Suppress("UNCHECKED_CAST") val keys = raw[0] as Array<String>
        @Suppress("UNCHECKED_CAST") val values = raw[1] as Array<Array<String>>
        val audio = raw[2] as IntArray
        return RawTags(
            properties = keys.indices.associate { keys[it] to values[it].toList() },
            durationMs = audio[0].toLong(),
            sampleRate = audio[1],
            bitrateKbps = audio[2],
            channels = audio[3],
            cover = raw[3] as ByteArray?,
        )
    }

    /** Object[] { String[] keys, String[][] values, int[] {lengthMs, sampleRate, bitrateKbps, channels}, byte[]? cover }. */
    private external fun nativeRead(fd: Int, fileNameHint: String): Array<Any?>?
}
