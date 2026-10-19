package app.granger.core.player

import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.media.audiofx.LoudnessEnhancer
import android.util.Log

/** Audio effects on the player's session. A seam: tests use [NONE]. */
interface AudioEffects {
    /** The player's audio session is [sessionId] (it may change); [speech] is the content type for equalizers. */
    fun open(sessionId: Int, speech: Boolean)

    /** Volume boost in millibels; 0 is off. Audiobooks only (the engine passes 0 for music). */
    fun setBoost(mb: Int)

    fun close()

    companion object {
        val NONE = object : AudioEffects {
            override fun open(sessionId: Int, speech: Boolean) = Unit
            override fun setBoost(mb: Int) = Unit
            override fun close() = Unit
        }
    }
}

/**
 * Exposes the audio session to external equalizers (e.g. Wavelet) the way Auxio does, with the standard open/close
 * effect-control broadcasts, and drives a [LoudnessEnhancer] for the audiobook volume boost.
 */
class EffectsSession(private val context: Context) : AudioEffects {
    private var sessionId = 0
    private var speech = false
    private var enhancer: LoudnessEnhancer? = null
    private var boostMb = 0

    override fun open(sessionId: Int, speech: Boolean) {
        if (sessionId == this.sessionId && speech == this.speech) return
        if (this.sessionId != 0 && this.sessionId != sessionId) close()
        this.sessionId = sessionId
        this.speech = speech
        if (sessionId == 0) return
        context.sendBroadcast(Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
            .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
            .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
            .putExtra(AudioEffect.EXTRA_CONTENT_TYPE, if (speech) AudioEffect.CONTENT_TYPE_VOICE else AudioEffect.CONTENT_TYPE_MUSIC))
        applyBoost()
    }

    override fun setBoost(mb: Int) {
        boostMb = mb.coerceAtLeast(0)
        applyBoost()
    }

    private fun applyBoost() {
        if (sessionId == 0) return
        try {
            if (boostMb == 0) {
                enhancer?.enabled = false
                return
            }
            val e = enhancer ?: LoudnessEnhancer(sessionId).also { enhancer = it }
            e.setTargetGain(boostMb)
            e.enabled = true
        } catch (e: RuntimeException) {
            Log.w(TAG, "Volume boost unavailable", e)   // some devices refuse the effect; play on without it
        }
    }

    override fun close() {
        enhancer?.release()
        enhancer = null
        if (sessionId != 0) {
            context.sendBroadcast(Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
                .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
                .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName))
        }
        sessionId = 0
    }

    private companion object { const val TAG = "EffectsSession" }
}
