package app.granger.core.speech

/** Audio as the voice passes it around: mono float samples in [-1, 1] at [sampleRate]. */
class Pcm(val samples: FloatArray, val sampleRate: Int) {
    val durationMs: Long get() = if (sampleRate <= 0) 0L else samples.size * 1000L / sampleRate

    companion object {
        /** [ms] of silence at [sampleRate]: the pauses between sentences are written as audio so the clock stays true. */
        fun silence(ms: Long, sampleRate: Int): Pcm = Pcm(FloatArray(((ms * sampleRate + 999) / 1000).toInt().coerceAtLeast(0)), sampleRate)
    }
}
