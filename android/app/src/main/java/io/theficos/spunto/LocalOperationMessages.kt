package io.theficos.spunto

import android.content.res.Resources
import io.theficos.spunto.core.model.LocalOperationException
import io.theficos.spunto.core.model.LocalOperationProblem

/** Formats an explicit domain reason; diagnostic/user content is never used as a translation key. */
internal fun localOperationMessage(resources:Resources,error:LocalOperationException):String=resources.getString(when(error.problem) {
 LocalOperationProblem.NoteUnavailable -> R.string.local_problem_note_unavailable
 LocalOperationProblem.EditorChanged -> R.string.local_problem_editor_changed
 LocalOperationProblem.EditorChangedWhileSaving -> R.string.local_problem_editor_changed_while_saving
 LocalOperationProblem.OriginalUnavailable -> R.string.local_problem_original_unavailable
 LocalOperationProblem.NoteTooLarge -> R.string.local_problem_note_too_large
 LocalOperationProblem.ImageTooLarge -> R.string.local_problem_image_too_large
 LocalOperationProblem.RecordingJournalRequired -> R.string.local_problem_recording_journal_required
 LocalOperationProblem.RecordingPathInvalid -> R.string.local_problem_recording_path_invalid
 LocalOperationProblem.VoiceMemosDisabled -> R.string.local_problem_voice_memos_disabled
 LocalOperationProblem.RecordingUnavailable -> R.string.local_problem_recording_unavailable
 LocalOperationProblem.RecordingAlreadyHandled -> R.string.local_problem_recording_already_handled
 LocalOperationProblem.RecordingStateChanged -> R.string.local_problem_recording_state_changed
 LocalOperationProblem.RecordingNotReady -> R.string.local_problem_recording_not_ready
 LocalOperationProblem.RecordingOriginalNoteRequired -> R.string.local_problem_recording_original_note_required
 LocalOperationProblem.RecordingCurrentEditorRequired -> R.string.local_problem_recording_current_editor_required
 LocalOperationProblem.RecordingChanged -> R.string.local_problem_recording_changed
 LocalOperationProblem.RecordingOriginalServerRequired -> R.string.local_problem_recording_original_server_required
 LocalOperationProblem.NoteChangedWhileSaving -> R.string.local_problem_note_changed_while_saving
 LocalOperationProblem.NoteChangedDuringRecording -> R.string.local_problem_note_changed_during_recording
 LocalOperationProblem.RecordingAttachmentMustBeRemoved -> R.string.local_problem_recording_attachment_must_be_removed
 LocalOperationProblem.RecordingInvalid -> R.string.local_problem_recording_invalid
 LocalOperationProblem.RecordingLimitOrUnavailable -> R.string.local_problem_recording_limit_or_unavailable
 LocalOperationProblem.AttachmentLimitOrUnavailable -> R.string.local_problem_attachment_limit_or_unavailable
 LocalOperationProblem.RecordingNothingToRecover -> R.string.local_problem_recording_nothing_to_recover
 LocalOperationProblem.UnencryptedConnectionConsentRequired -> R.string.local_problem_unencrypted_connection_consent_required
 LocalOperationProblem.ServerRootRequired -> R.string.local_problem_server_root_required
 LocalOperationProblem.AccountIdentityIncomplete -> R.string.local_problem_account_identity_incomplete
 LocalOperationProblem.AttachmentIdInvalid -> R.string.local_problem_attachment_id_invalid
 LocalOperationProblem.LabelInvalid -> R.string.local_problem_label_invalid
 LocalOperationProblem.UnfinishedCodeBlock -> R.string.local_problem_unfinished_code_block
 LocalOperationProblem.SuggestionNoLongerPending -> R.string.local_problem_suggestion_no_longer_pending
 LocalOperationProblem.LabelReviewOnWebRequired -> R.string.local_problem_label_review_on_web_required
 LocalOperationProblem.ReplacementSuperseded -> R.string.local_problem_replacement_superseded
 LocalOperationProblem.MergeSuperseded -> R.string.local_problem_merge_superseded
})
