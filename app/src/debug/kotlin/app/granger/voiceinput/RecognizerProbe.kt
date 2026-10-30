package app.granger.voiceinput

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.ModelDownloadListener
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import app.granger.core.ai.SystemMonoClock
import app.granger.core.speech.Pcm
import java.io.FileOutputStream
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CoroutineScope
import org.json.JSONArray
import org.json.JSONObject

/**
 * Debug-only (Plan A0): asks the phone what speech recognition it has and feeds it audio through a pipe
 * (`EXTRA_AUDIO_SOURCE`, API 33+). Every call must come from the main thread, as `SpeechRecognizer` requires;
 * each returns one JSON object for an `Asr:` log line and never throws.
 */
class RecognizerProbe(private val context: Context, private val scope: CoroutineScope) {
    private val main = ContextCompat.getMainExecutor(context)

    /** The recognition services installed, and the two availability checks. */
    fun services(): JSONObject {
        val found = context.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
            .map { ComponentName(it.serviceInfo.packageName, it.serviceInfo.name).flattenToShortString() }
        return JSONObject()
            .put("recognitionAvailable", SpeechRecognizer.isRecognitionAvailable(context))
            .put("onDeviceAvailable", SpeechRecognizer.isOnDeviceRecognitionAvailable(context))
            .put("services", JSONArray(found))
    }

    /** `checkRecognitionSupport` on the on-device recognizer for [language]. */
    suspend fun support(language: String): JSONObject = withTimeoutOrNull(10_000) {
        suspendCancellableCoroutine { cont ->
            val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            cont.invokeOnCancellation { recognizer.destroy() }
            recognizer.checkRecognitionSupport(intent(language), main, object : RecognitionSupportCallback {
                override fun onSupportResult(s: RecognitionSupport) {
                    recognizer.destroy()
                    cont.resume(
                        JSONObject().put("language", language)
                            .put("installed", JSONArray(s.installedOnDeviceLanguages))
                            .put("pending", JSONArray(s.pendingOnDeviceLanguages))
                            .put("supported", JSONArray(s.supportedOnDeviceLanguages))
                            .put("online", JSONArray(s.onlineLanguages)),
                    )
                }

                override fun onError(error: Int) {
                    recognizer.destroy()
                    cont.resume(JSONObject().put("language", language).put("error", errorName(error)))
                }
            })
        }
    } ?: JSONObject().put("language", language).put("error", "timeout")

    /** `triggerModelDownload` for [language]; [onProgress] gets the percentages as they come. */
    suspend fun download(language: String, onProgress: (Int) -> Unit): JSONObject {
        val start = SystemMonoClock.nanos()
        val outcome = withTimeoutOrNull(10 * 60_000L) {
            suspendCancellableCoroutine { cont ->
                val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                cont.invokeOnCancellation { recognizer.destroy() }
                fun done(what: String) { recognizer.destroy(); if (cont.isActive) cont.resume(what) }
                recognizer.triggerModelDownload(intent(language), main, object : ModelDownloadListener {
                    override fun onProgress(completedPercent: Int) = onProgress(completedPercent)
                    override fun onSuccess() = done("success")
                    override fun onScheduled() = done("scheduled")
                    override fun onError(error: Int) = done("error ${errorName(error)}")
                })
            }
        } ?: "timeout"
        return JSONObject().put("language", language).put("outcome", outcome).put("ms", msSince(start))
    }

    /**
     * Recognizes [pcm] (mono, any rate) fed through a pipe. With [paced] the audio is written in 20 ms chunks at
     * real time, as a microphone would deliver it; without, as fast as the pipe takes it. [tailMs] of silence goes
     * after the audio so the endpointer can hear the end. Times are from `startListening`; `finalAfterAudioMs` is
     * from the last byte written to the final result, the figure the adopt rule is about.
     */
    suspend fun recognize(
        pcm: Pcm,
        language: String = "en-US",
        bias: List<String>? = null,
        paced: Boolean = true,
        tailMs: Int = 300,
        partials: Boolean = true,
        segmented: Boolean = false,
    ): JSONObject {
        val out = JSONObject().put("language", language).put("bias", bias?.size ?: 0).put("paced", paced).put("tailMs", tailMs).put("partials", partials).put("segmented", segmented)
            .put("audioMs", pcm.samples.size * 1000L / pcm.sampleRate)
        val bytes = pcm16(pcm, tailMs)
        val start = SystemMonoClock.nanos()
        var audioEnd = 0L
        val result = withTimeoutOrNull(30_000) {
            suspendCancellableCoroutine { cont ->
                val (read, write) = ParcelFileDescriptor.createPipe()
                val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                fun finish(json: JSONObject) {
                    recognizer.destroy()
                    runCatching { read.close() }
                    if (cont.isActive) cont.resume(json)
                }
                cont.invokeOnCancellation { recognizer.destroy(); runCatching { read.close() }; runCatching { write.close() } }
                recognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) { out.put("readyMs", msSince(start)) }
                    override fun onBeginningOfSpeech() { out.put("beginMs", msSince(start)) }
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() { out.put("endOfSpeechMs", msSince(start)) }
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                    override fun onPartialResults(partial: Bundle?) {
                        partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { out.put("lastPartial", it) }
                        if (!out.has("firstPartialMs")) {
                            out.put("firstPartialMs", msSince(start))
                            out.put("firstPartial", partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull())
                        }
                    }

                    override fun onResults(results: Bundle?) {
                        out.put("finalMs", msSince(start))
                        if (audioEnd > 0) out.put("finalAfterAudioMs", (SystemMonoClock.nanos() - audioEnd) / 1_000_000)
                        out.put("results", JSONArray(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()))
                        results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)?.let { c -> out.put("confidence", JSONArray(c.toList())) }
                        results?.let { b -> out.put("resultKeys", JSONObject().apply { for (k in b.keySet()) put(k, describe(b, k)) }) }
                        finish(out)
                    }

                    // A segmented session (`EXTRA_SEGMENTED_SESSION` = `EXTRA_AUDIO_SOURCE`) reports each segment here
                    // and ends with onEndOfSegmentedSession instead of onResults.
                    override fun onSegmentResults(segment: Bundle) {
                        if (!out.has("firstSegmentMs")) out.put("firstSegmentMs", msSince(start))
                        val segments = out.optJSONArray("segments") ?: JSONArray().also { out.put("segments", it) }
                        segments.put(segment.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull())
                    }

                    override fun onEndOfSegmentedSession() {
                        out.put("finalMs", msSince(start))
                        if (audioEnd > 0) out.put("finalAfterAudioMs", (SystemMonoClock.nanos() - audioEnd) / 1_000_000)
                        finish(out)
                    }

                    override fun onError(error: Int) { finish(out.put("error", errorName(error)).put("errorMs", msSince(start))) }
                })
                val intent = intent(language).apply {
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, partials)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, read)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, pcm.sampleRate)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                    if (bias != null) putExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, ArrayList(bias))
                    if (segmented) putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
                }
                recognizer.startListening(intent)
                scope.launch(Dispatchers.IO) {
                    val chunk = pcm.sampleRate / 50 * 2 // 20 ms of 16-bit mono
                    runCatching {
                        FileOutputStream(write.fileDescriptor).use { stream ->
                            var at = 0
                            while (at < bytes.size) {
                                val n = minOf(chunk, bytes.size - at)
                                stream.write(bytes, at, n)
                                at += n
                                if (paced) Thread.sleep(20)
                            }
                        }
                    }.onFailure { out.put("writeError", it.toString()) }
                    audioEnd = SystemMonoClock.nanos()
                    out.put("audioWrittenMs", msSince(start))
                    runCatching { write.close() }
                }
            }
        }
        return result ?: out.put("error", "timeout")
    }

    @Suppress("DEPRECATION")
    private fun describe(b: Bundle, key: String): String = when (val v = b.get(key)) {
        is FloatArray -> v.toList().toString()
        is IntArray -> v.toList().toString()
        is LongArray -> v.toList().toString()
        else -> v.toString()
    }

    private fun intent(language: String) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
    }

    private fun msSince(start: Long) = (SystemMonoClock.nanos() - start) / 1_000_000

    companion object {
        /** Little-endian 16-bit PCM of [pcm] with [tailMs] of silence after it. */
        fun pcm16(pcm: Pcm, tailMs: Int): ByteArray {
            val tail = pcm.sampleRate * tailMs / 1000
            val out = ByteArray((pcm.samples.size + tail) * 2)
            pcm.samples.forEachIndexed { i, s ->
                val v = (s.coerceIn(-1f, 1f) * 32767f).toInt()
                out[2 * i] = v.toByte()
                out[2 * i + 1] = (v shr 8).toByte()
            }
            return out
        }

        fun errorName(error: Int): String = when (error) {
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "NETWORK_TIMEOUT"
            SpeechRecognizer.ERROR_NETWORK -> "NETWORK"
            SpeechRecognizer.ERROR_AUDIO -> "AUDIO"
            SpeechRecognizer.ERROR_SERVER -> "SERVER"
            SpeechRecognizer.ERROR_CLIENT -> "CLIENT"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "SPEECH_TIMEOUT"
            SpeechRecognizer.ERROR_NO_MATCH -> "NO_MATCH"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "RECOGNIZER_BUSY"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "INSUFFICIENT_PERMISSIONS"
            SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "TOO_MANY_REQUESTS"
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "SERVER_DISCONNECTED"
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "LANGUAGE_NOT_SUPPORTED"
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "LANGUAGE_UNAVAILABLE"
            SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT -> "CANNOT_CHECK_SUPPORT"
            SpeechRecognizer.ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS -> "CANNOT_LISTEN_TO_DOWNLOAD_EVENTS"
            else -> "error $error"
        }
    }
}
