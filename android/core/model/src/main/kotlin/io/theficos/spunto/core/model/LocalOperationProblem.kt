package io.theficos.spunto.core.model

import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract

/** Stable reasons for local guidance. Diagnostic text is not display-language content. */
enum class LocalOperationProblem {
 NoteUnavailable,
 EditorChanged,
 EditorChangedWhileSaving,
 OriginalUnavailable,
 NoteTooLarge,
 ImageTooLarge,
 RecordingJournalRequired,
 RecordingPathInvalid,
 VoiceMemosDisabled,
 RecordingUnavailable,
 RecordingAlreadyHandled,
 RecordingStateChanged,
 RecordingNotReady,
 RecordingOriginalNoteRequired,
 RecordingCurrentEditorRequired,
 RecordingChanged,
 RecordingOriginalServerRequired,
 NoteChangedWhileSaving,
 NoteChangedDuringRecording,
 RecordingAttachmentMustBeRemoved,
 RecordingInvalid,
 RecordingLimitOrUnavailable,
 AttachmentLimitOrUnavailable,
 RecordingNothingToRecover,
 UnencryptedConnectionConsentRequired,
 ServerRootRequired,
 AccountIdentityIncomplete,
 AttachmentIdInvalid,
 LabelInvalid,
 UnfinishedCodeBlock,
 SuggestionNoLongerPending,
 LabelReviewOnWebRequired,
 ReplacementSuperseded,
 MergeSuperseded,
}
class LocalOperationException(val problem:LocalOperationProblem,diagnostic:String=problem.name):IllegalArgumentException(diagnostic)

/** The contract preserves the null/type checks of the original require guards. */
@OptIn(ExperimentalContracts::class)
inline fun requireLocal(value:Boolean,problem:LocalOperationProblem,lazyMessage:()->Any={problem.name}) {
 contract { returns() implies value }
 if(!value) throw LocalOperationException(problem,lazyMessage().toString())
}
