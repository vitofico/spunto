package io.theficos.spunto.plugins.dictation

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

enum class DictationPhase { Idle, Starting, Listening, Stopping, NeedsPermission, NeedsLanguage, Failed }
enum class DictationNoticeKind { Permission, MicrophoneBusy, Starting, ListeningOnline, ListeningLocal, LanguageDownload, Stopped, NoSpeech, OfflineUnavailable, Finishing }
data class DictationState(val phase: DictationPhase = DictationPhase.Idle, val text: String = "", val partial: Boolean = false, val notice: DictationNoticeKind? = null, val language: String = "", val level: Float = 0f, val partialSpan: IntRange? = null)
interface AudioFocus { fun acquire(): Boolean; fun release() }
class DictationSession(
    private val scope: CoroutineScope,
    private val factory: () -> SpeechEngine,
    private val focus: AudioFocus,
    private val persist: suspend (String) -> Unit,
    private val logError: (Int) -> Unit = {},
) {
    private val mutable = MutableStateFlow(DictationState())
    val state: StateFlow<DictationState> = mutable.asStateFlow()
    private var engine: SpeechEngine? = null
    private var buffer = TranscriptBuffer("", 0)
    private var collector: Job? = null
    private var timeout: Job? = null
    private var silence: Job? = null
    private var generation = 0
    private var lastPartialWrite = 0L
    fun start(text: String, start: Int, end: Int, language: String, permission: Boolean, options: SpeechOptions = SpeechOptions()) {
        if (!permission) { stopAndKeep(); mutable.value = DictationState(DictationPhase.NeedsPermission, text, notice = DictationNoticeKind.Permission); return }
        finish()
        buffer = TranscriptBuffer(text, start, end)
        if (!focus.acquire()) { mutable.value = DictationState(DictationPhase.Failed, text, notice = DictationNoticeKind.MicrophoneBusy); return }
        launchEngine(language, options, false)
    }
    private fun launchEngine(language: String, options: SpeechOptions, retry: Boolean) {
        val current = ++generation
        mutable.value = DictationState(DictationPhase.Starting, buffer.text, notice = DictationNoticeKind.Starting, language=language)
        engine = factory()
        collector = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            engine!!.start(language, options).collect { event ->
                if (current != generation || state.value.phase !in setOf(DictationPhase.Starting, DictationPhase.Listening, DictationPhase.Stopping)) return@collect
                when (event) {
                    SpeechEvent.Ready -> { timeout?.cancel(); mutable.value = state.value.copy(phase = DictationPhase.Listening, notice = if(options.online) DictationNoticeKind.ListeningOnline else DictationNoticeKind.ListeningLocal); resetSilence() }
                    is SpeechEvent.Level -> mutable.value = state.value.copy(level = event.value)
                    is SpeechEvent.Partial -> { mutable.value = state.value.copy(text = buffer.partial(event.text), partial = buffer.hasPartial, partialSpan=buffer.partialSpan); resetSilence(); val now = System.nanoTime(); if (now-lastPartialWrite > 1_000_000_000L) { lastPartialWrite=now; persist(buffer.text) } }
                    is SpeechEvent.Final -> { mutable.value = state.value.copy(text = buffer.final(event.text), partial = false, partialSpan=null); persist(buffer.text); resetSilence() }
                    SpeechEvent.End -> { commitAndFinish() }
                    is SpeechEvent.LanguageDetected -> Unit
                    is SpeechEvent.Error -> {
                        logError(event.code)
                        val hasWords = buffer.hasCapture
                        val notice = when(event.reason) {
                            SpeechFailure.Permission -> DictationNoticeKind.Permission
                            SpeechFailure.Language -> DictationNoticeKind.LanguageDownload
                            SpeechFailure.Empty -> if(hasWords) DictationNoticeKind.Stopped else DictationNoticeKind.NoSpeech
                            SpeechFailure.Microphone -> DictationNoticeKind.MicrophoneBusy
                            SpeechFailure.Network -> DictationNoticeKind.OfflineUnavailable
                            SpeechFailure.Stopped -> DictationNoticeKind.Stopped
                        }
                        commitAndFinish()
                        mutable.value = state.value.copy(phase = when(event.reason) { SpeechFailure.Permission -> DictationPhase.NeedsPermission; SpeechFailure.Language -> DictationPhase.NeedsLanguage; SpeechFailure.Empty -> DictationPhase.Idle; else -> DictationPhase.Failed }, notice=notice,language=language)
                    }
                }
            }
        }
        timeout = scope.launch {
            delay(2000)
            if (current == generation && state.value.phase == DictationPhase.Starting) {
                collector?.cancel(); engine?.destroy()
                if (!retry) launchEngine(language, options, true) else { commitAndFinish(); mutable.value=state.value.copy(phase=DictationPhase.Failed,notice=DictationNoticeKind.Stopped) }
            }
        }
    }
    private fun resetSilence() { silence?.cancel(); silence=scope.launch { delay(10_000); stopAndKeep() } }
    suspend fun stopForSave(): String {
        if (state.value.phase in setOf(DictationPhase.Listening, DictationPhase.Starting)) {
            mutable.value=state.value.copy(phase=DictationPhase.Stopping,notice=DictationNoticeKind.Finishing)
            engine?.stop()
            withTimeoutOrNull(1500) { state.first { it.phase != DictationPhase.Stopping } }
        }
        commitAndFinish()
        persist(buffer.text)
        return buffer.text
    }
    /** Drops a notice that no longer applies (a download asked for the previous language) without touching the text. */
    fun clearNotice() { if(state.value.phase !in setOf(DictationPhase.Starting,DictationPhase.Listening,DictationPhase.Stopping)) mutable.value=DictationState(text=state.value.text) }
    fun stopAndKeep() { if(state.value.phase in setOf(DictationPhase.Starting,DictationPhase.Listening,DictationPhase.Stopping)) commitAndFinish() }
    private fun commitAndFinish() {
        val text=buffer.commitPartial()
        finish()
        mutable.value=DictationState(text=text,notice=DictationNoticeKind.Stopped)
        scope.launch { persist(text) }
    }
    private fun finish() { generation++; timeout?.cancel(); silence?.cancel(); collector?.cancel(); engine?.stop(); engine?.destroy(); engine=null; focus.release() }
}
