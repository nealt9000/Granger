package app.granger.voiceinput

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import app.granger.core.ai.SystemMonoClock
import org.json.JSONArray
import org.json.JSONObject

/**
 * Debug-only (Plan A0 Task 5): one live take from the microphone through the on-device recognizer, as A1 would do
 * it: `en-US`, partials on, no biasing (Neal's call after Task 4). [onPartial] gets the text as it grows; [onDone]
 * gets one JSON object for an `Asr: live` line, with times from `startListening` and `eosToFinalMs`, the time from
 * `onEndOfSpeech` (or the Stop tap, whichever came first) to the final result. Main thread only.
 */
class LiveListener(private val context: Context) {
    private var recognizer: SpeechRecognizer? = null
    private var start = 0L
    private var endMark = 0L
    private var out = JSONObject()

    val listening get() = recognizer != null

    fun start(onPartial: (String) -> Unit, onDone: (JSONObject) -> Unit) {
        if (recognizer != null) return
        out = JSONObject().put("language", "en-US")
        start = SystemMonoClock.nanos()
        endMark = 0L
        val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        recognizer = r
        fun finish() {
            r.destroy()
            recognizer = null
            if (!out.has("final")) out.put("final", out.optString("lastPartial"))
            onDone(out)
        }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { out.put("readyMs", ms()) }
            override fun onBeginningOfSpeech() { out.put("beginMs", ms()) }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                out.put("endOfSpeechMs", ms())
                if (endMark == 0L) endMark = SystemMonoClock.nanos()
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onPartialResults(partial: Bundle?) {
                val text = partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
                if (text.isBlank()) return
                if (!out.has("firstPartialMs")) out.put("firstPartialMs", ms())
                out.put("lastPartial", text)
                onPartial(text)
            }
            override fun onResults(results: Bundle?) {
                out.put("finalMs", ms())
                if (endMark > 0) out.put("eosToFinalMs", (SystemMonoClock.nanos() - endMark) / 1_000_000)
                val all = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                out.put("results", JSONArray(all))
                all.firstOrNull()?.takeIf { it.isNotBlank() }?.let { out.put("final", it) }
                    ?: out.put("finalFromPartial", true)
                results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)?.let { out.put("confidence", JSONArray(it.toList())) }
                finish()
            }
            override fun onError(error: Int) {
                out.put("error", RecognizerProbe.errorName(error)).put("errorMs", ms())
                finish()
            }
        })
        r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        })
    }

    /** The Stop tap: ends listening now instead of waiting for the endpointer. */
    fun stop() {
        val r = recognizer ?: return
        out.put("stopTapMs", ms())
        if (endMark == 0L) endMark = SystemMonoClock.nanos()
        r.stopListening()
    }

    fun cancel() {
        recognizer?.destroy()
        recognizer = null
    }

    private fun ms() = (SystemMonoClock.nanos() - start) / 1_000_000
}
