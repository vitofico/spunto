package io.theficos.spunto.plugins.dictation

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.speech.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import androidx.annotation.RequiresApi

class PlatformSpeechEngine(private val context: Context) : SpeechEngine {
    private var recognizer: SpeechRecognizer? = null
    override fun start(language: String, options: SpeechOptions) = callbackFlow {
        check(android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
        val local = if(Build.VERSION.SDK_INT >= 31 && !options.online) SpeechRecognizer.createOnDeviceSpeechRecognizer(context) else SpeechRecognizer.createSpeechRecognizer(context)
        recognizer=local
        val intent = recognitionIntent(language, options)
        local.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { trySend(SpeechEvent.Ready) }
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) { trySend(SpeechEvent.Level(rmsdB)) }
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onError(error: Int) { trySend(SpeechEvent.Error(mapError(error), error)); close() }
            override fun onResults(results: Bundle?) { trySend(SpeechEvent.Final(words(results))); if(Build.VERSION.SDK_INT < 33) local.startListening(intent) else { trySend(SpeechEvent.End); close() } }
            override fun onPartialResults(partialResults: Bundle?) { trySend(SpeechEvent.Partial(words(partialResults))) }
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
            override fun onSegmentResults(segmentResults: Bundle) { trySend(SpeechEvent.Final(words(segmentResults))) }
            override fun onEndOfSegmentedSession() { trySend(SpeechEvent.End); close() }
            override fun onLanguageDetection(results: Bundle) { results.getString("detected_language")?.let { trySend(SpeechEvent.LanguageDetected(it)) } }
        })
        local.startListening(intent)
        awaitClose { local.cancel(); local.destroy(); if(recognizer === local) recognizer=null }
    }
    override fun stop() { recognizer?.stopListening() }
    override fun destroy() { recognizer?.cancel(); recognizer?.destroy(); recognizer=null }
    companion object {
        fun onDeviceAvailable(context: Context) = Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        fun available(context: Context, consent: Boolean) = onDeviceAvailable(context) || consent && SpeechRecognizer.isRecognitionAvailable(context)
        fun recognitionIntent(language: String, options: SpeechOptions) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, !options.online)
            if(Build.VERSION.SDK_INT >= 33) {
                putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 600_000)
                putExtra(RecognizerIntent.EXTRA_ENABLE_FORMATTING, RecognizerIntent.FORMATTING_OPTIMIZE_QUALITY)
            }
            if(Build.VERSION.SDK_INT >= 34 && options.auto) {
                putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true)
                putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES, arrayListOf("it-IT", "en-US"))
                putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH,RecognizerIntent.LANGUAGE_SWITCH_BALANCED)
                putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES,arrayListOf("it-IT","en-US"))
            }
        }
        private fun words(bundle: Bundle?) = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
        fun mapError(code: Int) = when(code) {
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> SpeechFailure.Permission
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> SpeechFailure.Language
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> SpeechFailure.Empty
            SpeechRecognizer.ERROR_AUDIO, SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> SpeechFailure.Microphone
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_SERVER_DISCONNECTED, SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> SpeechFailure.Network
            else -> SpeechFailure.Stopped
        }
        suspend fun languageSupport(context: Context, language: String): LanguageAvailability = withContext(Dispatchers.Main) {
            if(!onDeviceAvailable(context)) return@withContext LanguageAvailability.Unavailable
            if(Build.VERSION.SDK_INT < 33) return@withContext LanguageAvailability.Ready
            languageSupport33(context,language)
        }
        @RequiresApi(33)
        private suspend fun languageSupport33(context:Context,language:String):LanguageAvailability = suspendCancellableCoroutine { continuation ->
                val local=SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                continuation.invokeOnCancellation { android.os.Handler(context.mainLooper).post { local.destroy() } }
                local.checkRecognitionSupport(recognitionIntent(language, SpeechOptions()), context.mainExecutor, object : RecognitionSupportCallback {
                    @RequiresApi(33) override fun onSupportResult(support: RecognitionSupport) {
                        val result=when { support.installedOnDeviceLanguages.any { it.equals(language,true) } -> LanguageAvailability.Ready; support.supportedOnDeviceLanguages.any { it.equals(language,true) } || support.pendingOnDeviceLanguages.any { it.equals(language,true) } -> LanguageAvailability.Download; else -> LanguageAvailability.Unavailable }
                        local.destroy(); if(continuation.isActive) continuation.resume(result)
                    }
                    override fun onError(error: Int) { local.destroy(); if(continuation.isActive) continuation.resume(LanguageAvailability.Unknown) }
                })
        }
        suspend fun supportsAuto(context: Context): Boolean = withContext(Dispatchers.Main) {
            if(Build.VERSION.SDK_INT < 34 || !onDeviceAvailable(context)) return@withContext false
            kotlinx.coroutines.withTimeoutOrNull(3000) { autoSupport34(context) } ?: false
        }
        @RequiresApi(34)
        private suspend fun autoSupport34(context:Context):Boolean = suspendCancellableCoroutine { continuation ->
                val local=SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                continuation.invokeOnCancellation { android.os.Handler(context.mainLooper).post { local.destroy() } }
                local.checkRecognitionSupport(recognitionIntent("it-IT",SpeechOptions(auto=true)),context.mainExecutor,object:RecognitionSupportCallback {
                    @RequiresApi(34) override fun onSupportResult(support:RecognitionSupport) { local.destroy();if(continuation.isActive) continuation.resume(support.installedOnDeviceLanguages.isNotEmpty()) }
                    override fun onError(error:Int) { local.destroy();if(continuation.isActive) continuation.resume(false) }
                })
        }
        suspend fun download(context: Context, language: String): LanguageAvailability = withContext(Dispatchers.Main) {
            if(Build.VERSION.SDK_INT < 33 || !onDeviceAvailable(context)) return@withContext LanguageAvailability.Unavailable
            val local=SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            try { local.triggerModelDownload(recognitionIntent(language, SpeechOptions())); kotlinx.coroutines.delay(30_000) } finally { local.destroy() }
            kotlinx.coroutines.withTimeoutOrNull(5000) { languageSupport(context,language) } ?: LanguageAvailability.Unknown
        }
    }
}
enum class LanguageAvailability { Ready, Download, Unavailable, Unknown }
class PlatformAudioFocus(context: Context, private val lost: () -> Unit) : AudioFocus {
    private val manager=context.getSystemService(AudioManager::class.java)
    private val request=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE).setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()).setOnAudioFocusChangeListener { if(it < 0) lost() }.build()
    override fun acquire() = manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    override fun release() { manager.abandonAudioFocusRequest(request) }
}
