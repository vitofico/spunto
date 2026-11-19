@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package io.theficos.spunto

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.theficos.spunto.core.design.SpuntoSpacing
import io.theficos.spunto.core.model.Note
import io.theficos.spunto.core.plugins.*
import io.theficos.spunto.data.local.PluginOutputEntity
import io.theficos.spunto.data.local.viewMembership
import io.theficos.spunto.data.sync.ApiException
import io.theficos.spunto.data.sync.SyncStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal const val AgentInboxId = "agent-inbox"
internal const val AgentReviewCommand = "mark-reviewed"

internal enum class NoteAcceptance { Machine, AgentNote, AgentChanges }

internal fun supportsVersionedAgentReview(manifest: JsonObject): Boolean =
    "agent_review_versioned" in manifest.pluginStrings("features").orEmpty()

internal fun handledByAgentReviewPanel(pluginId: String, commandId: String?, versioned: Boolean): Boolean =
    versioned && pluginId == AgentInboxId && commandId == AgentReviewCommand

internal fun noteAcceptanceActions(
    note: Note, outputs: List<PluginOutputEntity>, manifest: JsonObject, writable: Boolean, owner: Boolean,
): List<NoteAcceptance> {
    if (!writable || note.trashed || note.conflictOf != null) return emptyList()
    val features = manifest.pluginStrings("features").orEmpty()
    val agentInboxActive = manifest.objects("plugins").any {
        it.string("id") == AgentInboxId && it.bool("enabled", true) &&
            it.string("state") !in setOf("disabled", "off", "failed", "waiting", "removed")
    }
    val reviewed = outputs.any { output ->
        (output.noteId == null || output.noteId == note.id) && viewMembership(output)?.let {
            it.noteId == note.id && it.pluginId == AgentInboxId && it.viewId == "reviewed"
        } == true
    }
    return buildList {
        if (note.machine && "note_adoption" in features) add(NoteAcceptance.Machine)
        if (owner && supportsVersionedAgentReview(manifest) && agentInboxActive && !reviewed) {
            when {
                note.sourceKind == "agent" -> add(NoteAcceptance.AgentNote)
                note.updatedViaAgent -> add(NoteAcceptance.AgentChanges)
            }
        }
    }
}

internal fun matchesAcceptanceRevision(displayed: Note, current: Note?): Boolean {
    if (current == null || current.trashed || current.pending || current.syncError != null) return false
    return displayed.id == current.id && displayed.serverRev > 0 && displayed.serverRev == current.serverRev &&
        displayed.body == current.body && displayed.machine == current.machine && displayed.origin == current.origin &&
        displayed.sourceKind == current.sourceKind && displayed.updatedViaAgent == current.updatedViaAgent
}

internal suspend fun acceptNoteRevision(model: SpuntoViewModel, note: Note, action: NoteAcceptance): Boolean {
    val resources = model.container.context.resources
    val config = model.auth.value
    val epoch = model.pluginReplicaEpoch
    fun authorized() = config != null && model.auth.value == config && model.pluginReplicaEpoch == epoch &&
        model.workspaceCanWrite.value && (action == NoteAcceptance.Machine || model.workspaceIsOwner.value)
    require(authorized() && model.online.value && config != null) { resources.getString(R.string.accept_note_connect) }
    val current = model.repository.observe(note.id).first()
    require(current != null && matchesAcceptanceRevision(note, current) && action in noteAcceptanceActions(
        current, model.repository.outputs().first(), model.manifest.value, model.workspaceCanWrite.value, model.workspaceIsOwner.value,
    )) { resources.getString(R.string.accept_note_stale) }
    val api = pluginApi(config, resources).guarded { authorized() }
    if (action == NoteAcceptance.Machine) {
        val response = api.post("/api/v1/notes/${note.id}/adopt", buildJsonObject { put("base_version", note.serverRev) })
        require(response.string("id") == note.id && response.pluginFlag("machine") == false) {
            resources.getString(R.string.settings_plugin_result_is_unavailable_refresh_and_try)
        }
    } else {
        api.post("/api/v1/plugins/$AgentInboxId/commands/$AgentReviewCommand", buildJsonObject {
            put("note_id", note.id)
            put("base_version", note.serverRev)
        })
    }
    val synchronized = model.container.sync.sync()
    if (!authorized()) throw CancellationException()
    if (synchronized == SyncStatus.NeedsSignIn) model.reauthenticate.value = true
    val latest = model.repository.observe(note.id).first()
    if (latest == null || latest.serverRev <= note.serverRev || action == NoteAcceptance.Machine) return true
    return noteAcceptanceActions(latest, model.repository.outputs().first(), model.manifest.value,
        model.workspaceCanWrite.value, model.workspaceIsOwner.value).none { it != NoteAcceptance.Machine }
}

@Composable
internal fun NoteAcceptancePanel(
    model: SpuntoViewModel,
    note: Note,
    actions: List<NoteAcceptance>,
    detail: NoteToolDetail,
    scope: CoroutineScope,
    onHistory: () -> Unit,
    onEdit: () -> Unit,
) {
    val online by model.online.collectAsState()
    val resources = LocalContext.current.resources
    val feedback = LocalFeedback.current
    var running by remember(note.id) { mutableStateOf<NoteAcceptance?>(null) }
    var acknowledged by remember(note.id, note.serverRev) { mutableStateOf(emptySet<NoteAcceptance>()) }
    var message by remember(note.id, note.serverRev) { mutableStateOf("") }
    val ready = online && !note.pending && note.syncError == null && note.serverRev > 0
    val visible = actions - acknowledged
    if (visible.isEmpty()) return
    val loading = detail.loading
    val agentReview = remember(detail.data) { parseAgentReview(detail.data) }

    fun submit(action: NoteAcceptance, edited: Boolean) {
        if (!ready || running != null) return
        running = action
        message = ""
        scope.launch {
            try {
                if (acceptNoteRevision(model, note, action)) {
                    acknowledged += action
                    val receipt = if (edited) R.string.accept_changes_done else R.string.accept_note_done
                    feedback.show(resources.getString(receipt))
                } else {
                    feedback.show(resources.getString(R.string.accept_note_stale))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: ApiException) {
                when (error.status) {
                    401 -> model.reauthenticate.value = true
                    409 -> {
                        model.synchronize().join()
                        feedback.show(resources.getString(R.string.accept_note_stale))
                    }
                    else -> message = pluginApiError(resources, error)
                }
            } catch (error: IllegalArgumentException) {
                message = error.message.orEmpty()
            } catch (_: Exception) {
                message = resources.getString(R.string.sys_server_unreachable)
            } finally {
                running = null
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(SpuntoSpacing.s8)) {
        visible.forEach { action ->
            val machine = action == NoteAcceptance.Machine
            val edited = when (agentReview.takeUnless { machine }) {
                AgentReviewDetail.Created -> false
                is AgentReviewDetail.Edited -> true
                null -> action == NoteAcceptance.AgentChanges
            }
            val title = when {
                machine -> R.string.accept_machine_heading
                edited -> R.string.accept_agent_changes_heading
                else -> R.string.accept_agent_heading
            }
            val description = when {
                machine && note.privateNote -> R.string.accept_machine_private
                machine -> R.string.accept_machine_body
                else -> R.string.accept_agent_body
            }
            val accept = when {
                actions.size > 1 && machine -> R.string.accept_use_as_mine
                actions.size > 1 && edited -> R.string.accept_agent_changes
                actions.size > 1 -> R.string.accept_agent_note
                edited -> R.string.accept_changes
                else -> R.string.accept_note
            }
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Column(Modifier.padding(SpuntoSpacing.s16), verticalArrangement = Arrangement.spacedBy(SpuntoSpacing.s8)) {
                    Text(resources.getString(title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
                    Text(resources.getString(description), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (!machine && edited) {
                        val base = (agentReview as? AgentReviewDetail.Edited)?.before
                        if (base != null) Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            shape = MaterialTheme.shapes.small,
                        ) {
                            Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()).padding(SpuntoSpacing.s12)) {
                                RevisionChangePreview(base, note.body)
                            }
                        }
                        else if (!note.pending && note.syncError == null) Text(
                            resources.getString(if (loading) R.string.accept_changes_loading else R.string.accept_changes_unavailable),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(SpuntoSpacing.s8)) {
                        Button(
                            enabled = ready && running == null && (machine || !loading),
                            modifier = Modifier.heightIn(min = 48.dp),
                            onClick = { submit(action, edited) },
                        ) { Text(resources.getString(if (running == action) R.string.accept_note_working else accept)) }
                        if (actions.size == 1 || !machine) TextButton(
                            enabled = running == null,
                            modifier = Modifier.heightIn(min = 48.dp),
                            onClick = if (edited) onHistory else onEdit,
                        ) { Text(resources.getString(if (edited) R.string.core_history else R.string.core_edit_note)) }
                    }
                }
            }
        }
        val status = message.ifBlank {
            when {
                !online -> resources.getString(R.string.accept_note_connect)
                !ready -> resources.getString(R.string.accept_note_wait_sync)
                else -> ""
            }
        }
        if (status.isNotBlank()) Text(
            status,
            style = MaterialTheme.typography.bodySmall,
            color = if (message.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}
