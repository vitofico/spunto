package io.theficos.spunto

import android.content.Context
import io.theficos.spunto.plugins.dictation.DictationNoticeKind
import io.theficos.spunto.plugins.dictation.DictationState
import io.theficos.spunto.plugins.dictation.voice.RecordingNotice
import io.theficos.spunto.plugins.dictation.voice.RecordingState
import java.util.Locale

/** Speech state contains notices, never display-language sentences or captured text. */
internal fun dictationMessage(context:Context,state:DictationState):String {
 val id=when(state.notice) {
  null -> return ""
  DictationNoticeKind.Permission -> R.string.sys_dictation_permission
  DictationNoticeKind.MicrophoneBusy -> R.string.sys_microphone_busy
  DictationNoticeKind.Starting -> R.string.sys_dictation_start
  DictationNoticeKind.ListeningOnline -> R.string.sys_listening_online
  DictationNoticeKind.ListeningLocal -> R.string.sys_listening_local
  DictationNoticeKind.LanguageDownload -> R.string.sys_dictation_language_download
  DictationNoticeKind.Stopped -> R.string.sys_stopped
  DictationNoticeKind.NoSpeech -> R.string.sys_no_speech
  DictationNoticeKind.OfflineUnavailable -> R.string.sys_dictation_offline
  DictationNoticeKind.Finishing -> R.string.sys_finishing
 }
 val language=Locale.forLanguageTag(state.language.ifBlank {"en-US"}).getDisplayLanguage(context.resources.configuration.locales[0])
 return if(state.notice in setOf(DictationNoticeKind.LanguageDownload,DictationNoticeKind.OfflineUnavailable)) context.getString(id,language) else context.getString(id)
}

internal fun recordingMessage(context:Context,state:RecordingState):String {
 val id=when(state.notice) {
 null -> return ""
 RecordingNotice.Starting -> R.string.sys_recording_start
 RecordingNotice.LiveTextUnavailable -> R.string.sys_recording_only
 RecordingNotice.StorageRetry -> R.string.sys_recording_storage_retry
 RecordingNotice.RecordingLocal -> R.string.sys_recording_local
 RecordingNotice.FinalizationRetry -> R.string.sys_recording_finalize_retry
 RecordingNotice.Kept -> R.string.sys_recording_kept
 RecordingNotice.Stopped -> R.string.sys_recording_stopped
 RecordingNotice.Permission -> R.string.sys_recording_permission
 RecordingNotice.Android13Required -> R.string.sys_recording_android13
 RecordingNotice.MicrophoneBusy -> R.string.sys_microphone_busy
 RecordingNotice.Unavailable -> R.string.sys_recording_unavailable
 }
 return context.getString(id)
}
