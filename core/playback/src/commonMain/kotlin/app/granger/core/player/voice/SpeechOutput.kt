package app.granger.core.player.voice

import app.granger.core.speech.Pcm

/**
 * The voice's own output, beside the phone's player: one streaming track for speech (Android's AudioTrack, the
 * iPhone's AVAudioEngine in Plan I5b). Writes are cooperative (they suspend
 * while the track's buffer is full and stop at cancellation), so Stop is immediate and nothing plays after it.
 * [volume] is the sleep timer's fade now and the mix's lever in V2.
 */
interface SpeechOutput : AutoCloseable {
    /** Readies the track for [sampleRate]; a different rate rebuilds it. Mono. */
    fun open(sampleRate: Int)
    suspend fun write(pcm: Pcm)
    fun play()
    fun pause()
    /** Drops everything written but not yet heard. */
    fun flush()
    var volume: Float
    /** Milliseconds of audio heard since the last [flush] or [open]. */
    val playedMs: Long
}
