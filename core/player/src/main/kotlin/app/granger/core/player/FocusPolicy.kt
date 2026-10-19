package app.granger.core.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log

/** Asks the system for audio focus. The seam that lets tests deliver focus changes. */
interface FocusRequester {
    enum class Result { GRANTED, DELAYED, FAILED }

    /**
     * Requests focus for speech or music, replacing any earlier request. [onChange] gets the AudioManager focus-change
     * codes. DELAYED: refused for now (a call), and [onChange] gets AUDIOFOCUS_GAIN when it's ours.
     */
    fun request(speech: Boolean, onChange: (Int) -> Unit): Result
    fun abandon()
}

/** The real one: an [AudioFocusRequest] (API 26+). Ducking is reported to us (we lower the volume ourselves). */
class SystemFocusRequester(context: Context) : FocusRequester {
    private val audio = context.getSystemService(AudioManager::class.java)
    private var current: AudioFocusRequest? = null

    override fun request(speech: Boolean, onChange: (Int) -> Unit): FocusRequester.Result {
        current?.let { audio.abandonAudioFocusRequest(it) }
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(if (speech) AudioAttributes.CONTENT_TYPE_SPEECH else AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            .setWillPauseWhenDucked(true)          // deliver CAN_DUCK to us instead of the system ducking silently
            .setAcceptsDelayedFocusGain(true)      // asked during a call: GAIN when it ends, instead of a flat no
            .setOnAudioFocusChangeListener({ onChange(it) }, Handler(Looper.getMainLooper()))
            .build()
        current = request
        return when (audio.requestAudioFocus(request)) {
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> FocusRequester.Result.GRANTED
            AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> FocusRequester.Result.DELAYED
            else -> { current = null; FocusRequester.Result.FAILED }
        }
    }

    override fun abandon() {
        current?.let { audio.abandonAudioFocusRequest(it) }
        current = null
    }
}

/**
 * The one owner of audio focus (Plan M1: one place, so project 3 can add a second stream such as read-aloud).
 * It turns focus changes into the four things the player does about them; the engine decides how.
 * Every change and request is logged under [TAG], so a device test can be read from logcat.
 */
class FocusPolicy(private val requester: FocusRequester) {
    interface Listener {
        /** A call or a navigation prompt: pause, and resume on [onGain]. */
        fun onTransientLoss()

        /** Another player took over: pause and stay paused. */
        fun onLoss()

        /** Focus is back (after a transient loss or a duck, or a delayed request is granted). */
        fun onGain()

        /** Something brief wants to talk over us: lower the volume until [onGain]. */
        fun onDuck()
    }

    var listener: Listener? = null

    /** A request of ours is with the system (held now, lost for now, or waiting for a delayed gain). */
    var held = false
        private set

    /** Held, but lost for now (a call), or waiting for a delayed gain: until [Listener.onGain] we may not play. */
    var suspended = false
        private set

    /**
     * True when we may play now: focus held (already, or granted now). During a transient loss the system is asked
     * again, so play pressed mid-call doesn't play over the call. The request accepts a delayed gain, so a call still
     * on answers DELAYED: false now, and [Listener.onGain] when the call ends.
     */
    fun request(speech: Boolean): Boolean {
        if (held && !suspended) return true
        val result = requester.request(speech, ::onChange)
        Log.i(TAG, "request(${if (speech) "speech" else "music"}) -> $result")
        held = result != FocusRequester.Result.FAILED
        suspended = result == FocusRequester.Result.DELAYED
        return result == FocusRequester.Result.GRANTED
    }

    fun abandon() {
        if (!held) return
        Log.i(TAG, "abandon")
        held = false
        suspended = false
        requester.abandon()
    }

    private fun onChange(change: Int) {
        Log.i(TAG, "onAudioFocusChange(${name(change)}) held=$held suspended=$suspended")
        val l = listener ?: run { Log.i(TAG, "no player: ignored"); return }
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> { held = true; suspended = false; l.onGain() }
            AudioManager.AUDIOFOCUS_LOSS -> { held = false; suspended = false; requester.abandon(); l.onLoss() }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> { suspended = true; l.onTransientLoss() }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> l.onDuck()
        }
    }

    companion object {
        const val TAG = "GrangerFocus"

        private fun name(change: Int) = when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> "GAIN"
            AudioManager.AUDIOFOCUS_LOSS -> "LOSS"
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> "LOSS_TRANSIENT"
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> "CAN_DUCK"
            else -> change.toString()
        }
    }
}
