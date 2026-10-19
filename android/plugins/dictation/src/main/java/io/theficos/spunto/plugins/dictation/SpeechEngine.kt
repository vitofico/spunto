package io.theficos.spunto.plugins.dictation

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

sealed interface SpeechEvent {
    data object Ready : SpeechEvent
    data class Level(val value: Float) : SpeechEvent
    data class Partial(val text: String) : SpeechEvent
    data class Final(val text: String) : SpeechEvent
    data class LanguageDetected(val tag: String) : SpeechEvent
    data object End : SpeechEvent
    data class Error(val reason: SpeechFailure, val code: Int = 0) : SpeechEvent
}
enum class SpeechFailure { Permission, Language, Empty, Microphone, Network, Stopped }
data class SpeechOptions(val online: Boolean = false, val auto: Boolean = false)
interface SpeechEngine {
    fun start(language: String, options: SpeechOptions = SpeechOptions()): Flow<SpeechEvent>
    fun stop()
    fun destroy()
}
class FakeSpeechEngine : SpeechEngine {
    private val events = MutableSharedFlow<SpeechEvent>(extraBufferCapacity = 32)
    var starts = 0; private set
    var stops = 0; private set
    override fun start(language: String, options: SpeechOptions): Flow<SpeechEvent> { starts++; return events.asSharedFlow() }
    suspend fun emit(event: SpeechEvent) { events.emit(event) }
    override fun stop() { stops++ }
    override fun destroy() = Unit
}
