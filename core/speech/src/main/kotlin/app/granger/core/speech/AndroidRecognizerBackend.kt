package app.granger.core.speech

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.ModelDownloadListener
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.annotation.TargetApi
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [RecognizerBackend] on `SpeechRecognizer.createOnDeviceSpeechRecognizer`. Needs Android 13 (API 33) for
 * `checkRecognitionSupport`, without which the app can't tell whether a language is installed; below that the mic
 * is hidden. Every call must come from the main thread.
 */
class AndroidRecognizerBackend(context: Context) : RecognizerBackend {
    private val context = context.applicationContext
    private val main get() = context.mainExecutor

    override fun onDeviceAvailable(): Boolean =
        Build.VERSION.SDK_INT >= 33 && runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false)

    override suspend fun support(): RecognizerLanguages? {
        if (Build.VERSION.SDK_INT < 33) return null
        return withTimeoutOrNull(10_000) { supportOn33() }
    }

    @TargetApi(33)
    private suspend fun supportOn33(): RecognizerLanguages? = suspendCancellableCoroutine { cont ->
        val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        cont.invokeOnCancellation { recognizer.destroy() }
        // The answer lists every language, whichever one is asked about.
        recognizer.checkRecognitionSupport(intent(RecognizerTags.ENGLISH_US), main, object : RecognitionSupportCallback {
            override fun onSupportResult(s: RecognitionSupport) {
                recognizer.destroy()
                if (cont.isActive) cont.resume(RecognizerLanguages(s.installedOnDeviceLanguages, s.supportedOnDeviceLanguages + s.pendingOnDeviceLanguages))
            }

            override fun onError(error: Int) {
                recognizer.destroy()
                if (cont.isActive) cont.resume(null)
            }
        })
    }

    override fun start(tag: String, listener: RecognizerListener): RecognizerSession {
        val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        val session = Session(recognizer)
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = listener.onReady()
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() = listener.onEndOfSpeech()
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onPartialResults(partial: Bundle?) {
                partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(listener::onPartial)
            }
            override fun onResults(results: Bundle?) = listener.onResults(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty())
            override fun onError(error: Int) = listener.onError(failureOf(error))
        })
        recognizer.startListening(intent(tag).apply {
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        })
        return session
    }

    override suspend fun download(tag: String, onProgress: (Int) -> Unit): DownloadOutcome {
        if (Build.VERSION.SDK_INT < 33) return DownloadOutcome.FAILED
        return withTimeoutOrNull(15 * 60_000L) { downloadOn33(tag, onProgress) } ?: DownloadOutcome.FAILED
    }

    @TargetApi(33)
    private suspend fun downloadOn33(tag: String, onProgress: (Int) -> Unit): DownloadOutcome = suspendCancellableCoroutine { cont ->
        val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        cont.invokeOnCancellation { recognizer.destroy() }
        fun done(outcome: DownloadOutcome) { recognizer.destroy(); if (cont.isActive) cont.resume(outcome) }
        recognizer.triggerModelDownload(intent(tag), main, object : ModelDownloadListener {
            override fun onProgress(completedPercent: Int) = onProgress(completedPercent)
            override fun onSuccess() = done(DownloadOutcome.DONE)
            override fun onScheduled() = done(DownloadOutcome.SCHEDULED)
            override fun onError(error: Int) = done(DownloadOutcome.FAILED)
        })
    }

    private class Session(private val recognizer: SpeechRecognizer) : RecognizerSession {
        private var freed = false
        override fun stop() { if (!freed) recognizer.stopListening() }
        override fun cancel() { if (!freed) { freed = true; recognizer.cancel(); recognizer.destroy() } }
        override fun release() { if (!freed) { freed = true; recognizer.destroy() } }
    }

    private fun intent(tag: String) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
    }

    companion object {
        fun failureOf(error: Int): DictationFailure = when (error) {
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> DictationFailure.NO_MATCH
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> DictationFailure.NO_PERMISSION
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_TOO_MANY_REQUESTS,
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED, SpeechRecognizer.ERROR_SERVER -> DictationFailure.BUSY
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> DictationFailure.UNAVAILABLE
            else -> DictationFailure.OTHER
        }
    }
}
