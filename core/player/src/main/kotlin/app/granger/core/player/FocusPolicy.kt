package app.granger.core.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper

/** Asks the system for audio focus. The seam that lets tests deliver focus changes. */
interface FocusRequester {
    /** Requests focus for speech or music; true when granted. [onChange] gets the AudioManager focus-change codes. */
    fun request(speech: Boolean, onChange: (Int) -> Unit): Boolean
    fun abandon()
}

/** The real one: an [AudioFocusRequest] (API 26+). Ducking is reported to us (we lower the volume ourselves). */
class SystemFocusRequester(context: Context) : FocusRequester {
    private val audio = context.getSystemService(AudioManager::class.java)
    private var current: AudioFocusRequest? = null

    override fun request(speech: Boolean, onChange: (Int) -> Unit): Boolean {
        current?.let { audio.abandonAudioFocusRequest(it) }
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(if (speech) AudioAttributes.CONTENT_TYPE_SPEECH else AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            .setWillPauseWhenDucked(true)          // deliver CAN_DUCK to us instead of the system ducking silently
            .setOnAudioFocusChangeListener({ onChange(it) }, Handler(Looper.getMainLooper()))
            .build()
        current = request
        return audio.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    override fun abandon() {
        current?.let { audio.abandonAudioFocusRequest(it) }
        current = null
    }
}

/**
 * The one owner of audio focus (Plan M1: one place, so project 3 can add a second stream such as read-aloud).
 * It turns focus changes into the four things the player does about them; the engine decides how.
 */
class FocusPolicy(private val requester: FocusRequester) {
    interface Listener {
        /** A call or a navigation prompt: pause, and resume on [onGain]. */
        fun onTransientLoss()

        /** Another player took over: pause and stay paused. */
        fun onLoss()

        /** Focus is back (after a transient loss or a duck). */
        fun onGain()

        /** Something brief wants to talk over us: lower the volume until [onGain]. */
        fun onDuck()
    }

    var listener: Listener? = null
    var held = false
        private set

    /** Held, but lost for now (a call): until [Listener.onGain], having it doesn't mean we may play. */
    private var suspended = false

    /**
     * True when focus is held (already, or granted now). During a transient loss the system is asked again, so play
     * pressed mid-call doesn't play over the call: it's refused there, and focus counts as not held. (Refused, the
     * old request is gone too, so the end of the call no longer resumes on its own; play does.)
     */
    fun request(speech: Boolean): Boolean {
        if (held && !suspended) return true
        held = requester.request(speech, ::onChange)
        suspended = false
        return held
    }

    fun abandon() {
        if (!held) return
        held = false
        suspended = false
        requester.abandon()
    }

    private fun onChange(change: Int) {
        val l = listener ?: return
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> { suspended = false; l.onGain() }
            AudioManager.AUDIOFOCUS_LOSS -> { held = false; suspended = false; requester.abandon(); l.onLoss() }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> { suspended = true; l.onTransientLoss() }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> l.onDuck()
        }
    }
}
