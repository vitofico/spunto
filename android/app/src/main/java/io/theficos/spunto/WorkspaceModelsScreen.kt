package io.theficos.spunto

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.theficos.spunto.data.sync.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

internal const val WorkspaceModelsRoute = "ai-models"

private val capabilityLabels = mapOf(
    "chat" to R.string.settings_chat,
    "embed" to R.string.settings_embeddings,
    "transcribe" to R.string.settings_transcription,
    "vision" to R.string.settings_vision,
)

@Composable
internal fun WorkspaceModelsScreen(model: SpuntoViewModel) {
    val config by model.auth.collectAsState()
    val owner by model.workspaceIsOwner.collectAsState()
    val online by model.online.collectAsState()
    val epoch by model.pluginReplicaEpochState.collectAsState()
    val resources = LocalContext.current.resources
    val session = config
    if (session == null || !owner) {
        Text(stringResource(if (session != null && !owner) R.string.models_owner else R.string.core_ask_connect), Modifier.padding(20.dp))
        return
    }
    key(session, epoch) {
        val api = remember(session, epoch, resources) {
            pluginApi(session, resources).guarded {
                model.workspaceIsOwner.value && model.auth.value == session &&
                    model.container.auth.config.first() == session && model.pluginReplicaEpoch == epoch
            }
        }
        WorkspaceModelsEditor(
            online = online,
            load = { api.workspaceModelSettings() },
            inventory = { api.workspaceModelInventory(it) },
            save = { saved, next -> api.saveWorkspaceModelSettings(saved, next) },
            message = { failure ->
                if (failure is ApiException && failure.status == 401 && model.auth.value == session &&
                    model.pluginReplicaEpoch == epoch && model.workspaceIsOwner.value) {
                    model.reauthenticate.value = true
                }
                when {
                    failure is ApiException && failure.status == 409 -> resources.getString(R.string.models_stale)
                    failure is ApiException -> pluginApiError(resources, failure)
                    else -> resources.getString(R.string.settings_couldn_t_reach_the_server_check_your)
                }
            },
        )
    }
}

@Composable
internal fun WorkspaceModelsEditor(
    online: Boolean,
    load: suspend () -> WorkspaceModelSettings,
    inventory: suspend (WorkspaceModelSettings) -> WorkspaceModelInventory,
    save: suspend (WorkspaceModelSettings, Map<String, WorkspaceModelChoice>) -> WorkspaceModelSettings,
    message: (Exception) -> String,
) {
    val scope = rememberCoroutineScope()
    var saved by remember { mutableStateOf<WorkspaceModelSettings?>(null) }
    var choices by remember { mutableStateOf(emptyMap<String, WorkspaceModelChoice>()) }
    var available by remember { mutableStateOf<WorkspaceModelInventory?>(null) }
    var busy by remember { mutableStateOf(true) }
    var stale by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var success by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf<String?>(null) }

    suspend fun reload() {
        busy = true
        error = ""
        success = false
        try {
            val result = load()
            saved = result
            choices = result.selections
            stale = false
            available = null
            if (result.canManage && result.revision != null) available = inventory(result)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = message(failure)
        } finally {
            busy = false
        }
    }
    LaunchedEffect(online) {
        if (!online) return@LaunchedEffect
        val existing = saved
        if (existing == null) reload()
        else if (available == null && existing.canManage && existing.revision != null) {
            busy = true
            try {
                available = inventory(existing)
                error = ""
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = message(failure)
            } finally {
                busy = false
            }
        }
    }

    val snapshot = saved
    val dirty = snapshot != null && choices != snapshot.selections
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 12.dp)) {
        if (!online) Text(stringResource(R.string.core_ask_connect), Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        else if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (snapshot?.canManage == true && snapshot.revision != null) {
            Text(stringResource(R.string.models_scope), Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            capabilityLabels.forEach { (capability, title) ->
                val selected = choices[capability]
                val effective = selected ?: snapshot.defaults[capability]
                val options = available?.optionsFor(capability, effective, snapshot.defaults[capability]).orEmpty()
                val summary = modelSummary(snapshot, effective, options.firstOrNull { it.choice == effective }, capability)
                SettingsRow(
                    title = stringResource(title),
                    summary = if (selected == null) stringResource(R.string.models_default, summary) else summary,
                    enabled = online && !busy && !stale && (options.isNotEmpty() || selected != null),
                    opensPage = true, onClick = { picker = capability },
                )
            }
            if (choices["embed"] != snapshot.selections["embed"]) {
                Text(stringResource(R.string.models_embedding_rebuild), Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            }
        } else if (snapshot != null) {
            Text(stringResource(if (snapshot.canManage) R.string.settings_ai_model_summary_unavailable else R.string.models_owner),
                Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        }
        available?.failedProfiles.orEmpty().forEach { name ->
            Text(stringResource(R.string.models_provider_failed, name), Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                color = MaterialTheme.colorScheme.error)
        }
        if (error.isNotEmpty() || success) {
            Text(if (success) stringResource(R.string.models_saved) else error,
                Modifier.padding(horizontal = 20.dp, vertical = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
                color = if (success) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
        }
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (snapshot?.canManage == true && snapshot.revision != null) {
                Button(enabled = online && dirty && !busy && !stale, onClick = {
                    if (busy) return@Button
                    busy = true
                    val submitted = choices
                    scope.launch {
                        error = ""
                        try {
                            val result = save(snapshot, submitted)
                            saved = result
                            choices = result.selections
                            success = true
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            stale = failure is ApiException && failure.status == 409
                            error = message(failure)
                        } finally {
                            busy = false
                        }
                    }
                }) { Text(stringResource(R.string.models_save)) }
            }
            TextButton(enabled = online && !busy, onClick = { scope.launch { reload() } }) {
                Text(stringResource(if (dirty) R.string.models_discard_reload else R.string.models_refresh))
            }
        }
    }
    val capability = picker
    if (online && capability != null && snapshot != null) {
        val options = available?.optionsFor(capability, choices[capability] ?: snapshot.defaults[capability], snapshot.defaults[capability]).orEmpty()
        AlertDialog(
            onDismissRequest = { picker = null },
            title = { Text(stringResource(capabilityLabels.getValue(capability))) },
            text = {
                LazyColumn(Modifier.selectableGroup()) {
                    item {
                        val default = snapshot.defaults[capability]
                        ModelChoiceRow(
                            stringResource(R.string.models_default, modelSummary(snapshot, default, options.firstOrNull { it.choice == default }, capability)),
                            choices[capability] == null,
                        ) {
                            choices = choices - capability
                            picker = null
                            success = false
                        }
                    }
                    items(options) { option ->
                        ModelChoiceRow(modelSummary(snapshot, option.choice, option, capability), choices[capability] == option.choice) {
                            choices = choices + (capability to option.choice)
                            picker = null
                            success = false
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { picker = null }) { Text(stringResource(R.string.core_cancel)) } },
        )
    }
}

@Composable
private fun modelSummary(settings: WorkspaceModelSettings, choice: WorkspaceModelChoice?, option: WorkspaceModelOption?, capability: String): String {
    if (choice == null) return stringResource(R.string.models_off)
    return listOfNotNull(
        choice.model,
        option?.upstreamModel?.takeIf { it != choice.model },
        choice.profile,
        stringResource(when (option?.leavesNetwork ?: settings.leavesNetwork(capability, choice)) {
            true -> R.string.settings_outside_your_network
            false -> R.string.settings_on_your_network
            null -> R.string.models_unknown_network
        }),
        if (option?.unverified(capability) == true) stringResource(R.string.models_unverified) else null,
    ).joinToString(" · ")
}

@Composable
private fun ModelChoiceRow(text: String, selected: Boolean, choose: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected, role = Role.RadioButton, onClick = choose)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
