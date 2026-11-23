package io.theficos.spunto

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import io.theficos.spunto.core.design.SpuntoSpacing
import io.theficos.spunto.core.design.Markdown
import io.theficos.spunto.core.plugins.bool
import io.theficos.spunto.core.plugins.objects
import io.theficos.spunto.core.plugins.string
import kotlinx.serialization.json.*

internal const val DecisionManagerRoute = "decisions"
internal const val DecisionDetailPrefix = "decision:"

@Composable
internal fun DecisionManager(model: SpuntoViewModel, noteId: String?, onDetail: (String) -> Unit, onNote: (String) -> Unit, onEdit: (String) -> Unit, onBack: () -> Unit) {
    val manifest by model.manifest.collectAsState()
    val online by model.online.collectAsState()
    val owner by model.workspaceIsOwner.collectAsState()
    val auth by model.auth.collectAsState()
    val epoch by model.pluginReplicaEpochState.collectAsState()
    val plugin = manifest.objects("plugins").firstOrNull { it.string("id") == DecisionJournalId }
    val upgraded = decisionVersionAtLeast(plugin, 1)
    val route = DecisionViewRoute
    if (!online && noteId == null) {
        FilteredTimeline(model, route, onNote = onNote)
        return
    }
    if (!owner || plugin == null) {
        Text(stringResource(R.string.decision_manager_unavailable))
        return
    }
    if (!online) {
        Text(stringResource(R.string.settings_changes_need_a_connection))
        return
    }
    val scope = rememberCoroutineScope()
    var result by remember(auth, epoch, noteId) { mutableStateOf<JsonObject?>(null) }
    var search by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var due by remember { mutableStateOf(false) }
    var cursor by remember { mutableStateOf("") }
    var outcomeCursor by remember { mutableStateOf("") }
    var detailDialog by remember(noteId) { mutableStateOf<JsonObject?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var running by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var trash by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf(false) }
    val args = buildJsonObject {
        noteId?.let { put("note_id", it) }
        if (upgraded) {
            put("manager", true)
            if (query.isNotEmpty()) put("query", query)
            if (status.isNotEmpty()) put("status", status)
            if (due) put("due", due)
        }
        if (cursor.isNotEmpty()) put("cursor", cursor)
        if (outcomeCursor.isNotEmpty()) put("outcome_cursor", outcomeCursor)
    }
    LaunchedEffect(auth, epoch, args, refresh) {
        result = null
        error = ""
        running = true
        val request = runCommand(
            model, DecisionJournalId, "journal", args, { result = it }, { running = false },
            scope = this, onError = { error = it }, requireOwner = true,
        )
        request.join()
    }
    val components = result?.let(::decisionOutput).orEmpty()
    val original by remember(noteId) { model.repository.observe(noteId.orEmpty()) }.collectAsState(null)
    val assist = manifest.objects("plugins").any { it.string("id") == "note-assist" && it.bool("enabled", true) }
    val resources = LocalContext.current.resources
    fun openDetailAction(command: String, args: JsonObject) {
        running = true
        runCommand(model, DecisionJournalId, command, args, { detailDialog = it }, { running = false },
            scope = scope, onError = { error = it }, requireOwner = true)
    }
    val details = noteId != null && components.any { it.string("type") == "notes" }
    Column(
        Modifier.fillMaxSize().testTag(if (noteId == null) "decision-manager" else "decision-detail:$noteId")
            .verticalScroll(rememberScrollState()).padding(SpuntoSpacing.s16),
        verticalArrangement = Arrangement.spacedBy(SpuntoSpacing.s8),
    ) {
        if (noteId == null) {
            if (upgraded) {
                DecisionDraftFlow(model, null, onNote, onCreated = onDetail) { refresh++ }
                OutlinedTextField(search, { search = it.take(200) }, label = { Text(stringResource(R.string.decision_search)) }, singleLine = true)
                TextButton(onClick = { query = search.trim(); cursor = "" }) { Text(stringResource(R.string.core_search)) }
                DecisionStatusFilter(status) { status = it; cursor = "" }
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(due, { due = it; cursor = "" })
                    Text(stringResource(R.string.decision_only_due))
                }
            } else DecisionRecordAction(model)
        }
        if (running) Text(stringResource(R.string.decision_opening))
        if (error.isNotEmpty()) {
            Text(error, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { refresh++ }) { Text(stringResource(R.string.decision_refresh)) }
        }
        val response = result
        if (noteId != null && response != null) {
            val current = original
            val statusText = components.firstOrNull { it.string("type") == "text" }?.string("text").orEmpty()
            if (current != null && !current.trashed && details) {
                Text(current.title.ifBlank { stringResource(R.string.core_untitled) }, style = MaterialTheme.typography.headlineSmall)
                Text(localizedJournalLabel(resources, statusText), style = MaterialTheme.typography.bodyMedium)
                val body = current.body.takeUnless { it.startsWith("# ") } ?: current.body.substringAfter('\n', "").trimStart()
                Markdown(body, onLink = { if (it.startsWith("/n/")) onNote(it.removePrefix("/n/")) })
                val outputs by model.outputs.collectAsState()
                DecisionRationale(noteId, outputs)
                val reviewArgs = buildJsonObject { put("note_id", noteId) }
                if (upgraded && assist) {
                    Button(onClick = { draft = true }) { Text(stringResource(R.string.decision_what_happened)) }
                    TextButton(enabled = !running, onClick = { openDetailAction("review-decision", reviewArgs) }) {
                        Text(stringResource(R.string.decision_manual_outcome))
                    }
                } else {
                    Button(enabled = !running, onClick = { openDetailAction("review-decision", reviewArgs) }) {
                        Text(stringResource(R.string.decision_record_outcome))
                    }
                }
                Text(stringResource(R.string.decision_outcomes), style = MaterialTheme.typography.titleMedium)
                val outcomes = components.getOrNull(2)?.objects("items").orEmpty()
                if (outcomes.isEmpty()) Text(stringResource(R.string.decision_no_outcomes))
                outcomes.forEach { outcome ->
                    DecisionManagerReference(model, outcome.string("id"), outcome.string("reason"), onNote)
                }
                if (outcomeCursor.isNotEmpty()) TextButton(onClick = { outcomeCursor = "" }) {
                    Text(stringResource(R.string.decision_first_page))
                }
                val more = components.firstNotNullOfOrNull {
                    (it["args"] as? JsonObject)?.string("outcome_cursor")?.takeIf(String::isNotBlank)
                }
                more?.let { TextButton(onClick = { outcomeCursor = it }) { Text(stringResource(R.string.decision_more_outcomes)) } }
                TextButton(onClick = { onEdit(noteId) }) { Text(stringResource(R.string.decision_edit_original)) }
                val schedule = components.mapNotNull { it["args"] as? JsonObject }.firstOrNull { it.string("operation") == "schedule" }
                schedule?.let {
                    TextButton(enabled = !running, onClick = { openDetailAction("journal", it) }) {
                        Text(stringResource(R.string.decision_review_settings))
                    }
                }
                TextButton(onClick = { trash = true }) { Text(stringResource(R.string.decision_trash)) }
            } else if (statusText.isNotBlank()) Text(statusText)
        } else if (response != null) {
            components.firstOrNull { it.string("type") == "text" }?.let { Text(it.string("text")) }
            for (reference in components.firstOrNull { it.string("type") == "notes" }?.objects("items").orEmpty()) {
                val id = reference.string("id")
                if (id.isNotEmpty()) Box(Modifier.testTag("decision-list-row:$id")) {
                    DecisionManagerReference(model, id, reference.string("reason"), onDetail)
                }
            }
            if (cursor.isNotEmpty()) TextButton(onClick = { cursor = "" }) { Text(stringResource(R.string.decision_first_page)) }
            val next = components.firstNotNullOfOrNull {
                (it["args"] as? JsonObject)?.string("cursor")?.takeIf(String::isNotBlank)
            }
            next?.let { TextButton(onClick = { cursor = it }) { Text(stringResource(R.string.decision_more_decisions)) } }
        }
    }
    detailDialog?.let { output ->
        CommandResultSheet(model, output, onNote, { detailDialog = it }, { detailDialog = null; refresh++ })
    }
    if (trash && noteId != null) {
        AlertDialog(onDismissRequest = { trash = false }, title = { Text(stringResource(R.string.decision_trash)) },
            text = { Text(stringResource(R.string.decision_trash_explanation)) },
            confirmButton = { TextButton(onClick = {
                scope.launchWhenDecisionTrashed(model, noteId, onBack)
                trash = false
            }) { Text(stringResource(R.string.decision_trash)) } },
            dismissButton = { TextButton(onClick = { trash = false }) { Text(stringResource(R.string.settings_cancel)) } })
    }
    if (draft && noteId != null) {
        val close: () -> Unit = { draft = false; refresh++ }
        AlertDialog(
            onDismissRequest = close,
            title = { Text(stringResource(R.string.decision_what_happened)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) { DecisionDraftFlow(model, noteId, onNote) { refresh++ } } },
            confirmButton = { TextButton(onClick = close) { Text(stringResource(R.string.settings_close)) } },
        )
    }
}

private fun kotlinx.coroutines.CoroutineScope.launchWhenDecisionTrashed(model: SpuntoViewModel, id: String, onBack: () -> Unit) =
    model.task(this) {
        model.requireWorkspaceWrite()
        model.repository.setFlags(id, trashed = true)
        onBack()
    }

@Composable
private fun DecisionStatusFilter(value: String, onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val labels = mapOf("" to R.string.decision_all_status, "open" to R.string.decision_open,
        "reviewed" to R.string.decision_reviewed, "closed" to R.string.decision_closed)
    Box {
        TextButton(onClick = { open = true }) { Text(stringResource(R.string.decision_filter_status, stringResource(labels.getValue(value)))) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            labels.forEach { (status, label) ->
                DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = { onChange(status); open = false })
            }
        }
    }
}

@Composable
private fun DecisionManagerReference(model: SpuntoViewModel, id: String, reason: String, onDetail: (String) -> Unit) {
    val note by remember(model, id) { model.repository.observe(id) }.collectAsState(null)
    val current = note ?: return
    if (!current.trashed) ResultNoteLink(
        current.title.ifBlank { stringResource(R.string.core_untitled) },
        localizedJournalLabel(LocalContext.current.resources, reason),
    ) { onDetail(id) }
}

private fun decisionOutput(result: JsonObject): List<JsonObject> {
    val payload = result["payload"] as? JsonObject ?: return emptyList()
    return if (payload.string("type").isNotEmpty()) listOf(payload) else payload.objects("items")
}

@Composable
private fun DecisionDraftFlow(model: SpuntoViewModel, noteId: String?, onNote: (String) -> Unit, onCreated: (String) -> Unit = onNote, onSaved: () -> Unit) {
    val resources = LocalContext.current.resources
    val manifest by model.manifest.collectAsState()
    val available = manifest.objects("plugins").any { it.string("id") == "note-assist" && it.bool("enabled", true) }
    val online by model.online.collectAsState()
    val scope = rememberCoroutineScope()
    var phrase by remember { mutableStateOf("") }
    var answer by remember { mutableStateOf<JsonObject?>(null) }
    var candidate by remember { mutableStateOf<JsonObject?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var updateTitle by remember { mutableStateOf<String?>(null) }

    fun prepare(target: String? = noteId, title: String? = null) {
        val updating = target != null && noteId == null
        val command = if (target == null) "record-decision" else "review-decision"
        val proposed = answer?.get("draft") as? JsonObject ?: JsonObject(emptyMap())
        val values = when {
            updating -> buildJsonObject { put("outcome", phrase); put("status", "open") }
            target == null -> JsonObject(proposed + ("decision" to JsonPrimitive(phrase)))
            proposed.isEmpty() && phrase.isNotBlank() -> buildJsonObject { put("outcome", phrase) }
            else -> proposed
        }
        val match = if (updating) answer?.objects("matches")?.firstOrNull { it.string("id") == target } else null
        val sources = (answer?.objects("sources").orEmpty() +
            listOfNotNull(answer?.get("basis") as? JsonObject, match)).distinctBy { it.string("id") }
        busy = true
        error = ""
        runCommand(model, DecisionJournalId, command, buildJsonObject { target?.let { put("note_id", it) } }, { result ->
            val form = result["payload"] as? JsonObject
            val schema = form?.get("schema") as? JsonObject
            if (form?.string("type") != "form" || schema == null) {
                error = resources.getString(R.string.decision_resume_manually)
            } else {
                updateTitle = title
                candidate = applyDecisionDraft(command, target, schema, values, sources)
            }
        }, { busy = false }, scope = scope, onError = { error = it }, requireOwner = true)
    }

    Column(verticalArrangement = Arrangement.spacedBy(SpuntoSpacing.s8)) {
        if (candidate == null) {
        OutlinedTextField(phrase, { phrase = it.take(500); answer = null; candidate = null; error = "" },
            label = { Text(stringResource(if (noteId == null) R.string.decision_phrase else R.string.decision_since_focus)) },
            enabled = !busy, modifier = Modifier.fillMaxWidth(),
        )
        Button(enabled = !busy && online && (noteId != null || phrase.isNotBlank()), onClick = {
            if (!available) {
                prepare()
            } else {
                val config = model.auth.value ?: return@Button
                val epoch = model.pluginReplicaEpoch
                busy = true
                candidate = null
                answer = null
                error = ""
                model.task(scope) {
                    fun current() = model.auth.value == config && model.pluginReplicaEpoch == epoch &&
                        model.workspaceIsOwner.value && model.container.manifest.enabled("note-assist")
                    try {
                        val response = pluginApi(config, resources).guarded { current() }.post(
                            "/api/v1/note-assist/decisions",
                            buildJsonObject { put("phrase", phrase); noteId?.let { put("note_id", it) } },
                        )
                        if (current()) answer = response
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        if (current()) error = (failure as? io.theficos.spunto.data.sync.ApiException)
                            ?.let { pluginApiError(resources, it) } ?: resources.getString(R.string.settings_couldn_t_reach_the_server_check_your)
                    } finally { busy = false }
                }
            }
        }) { Text(stringResource(if (noteId == null) R.string.decision_continue_flow else R.string.decision_what_happened)) }
        }
        if (!available) TextButton(onClick = { model.pluginWeb("/settings/plugins/note-assist") }, enabled = online) {
            Text(stringResource(R.string.decision_enable_assist))
        }
        answer?.string("message")?.takeIf(String::isNotBlank)?.let { Text(it) }
        val matches = answer?.objects("matches").orEmpty()
        if (matches.isNotEmpty()) Text(stringResource(R.string.decision_related_decisions))
        for (match in matches) {
            ResultNoteLink(
                stringResource(R.string.decision_update_match, match.string("title")),
                match.string("passage"),
            ) { if (!busy) prepare(match.string("id"), match.string("title")) }
        }
        val sources = answer?.objects("sources").orEmpty()
        if (sources.isNotEmpty()) Text(stringResource(R.string.decision_evidence))
        for (source in sources) {
            val supports = (source["supports"] as? JsonArray).orEmpty().map { (it as? JsonPrimitive)?.contentOrNull }
            val labels = listOfNotNull(
                stringResource(R.string.decision_reason_evidence).takeIf { "reason" in supports },
                stringResource(R.string.decision_questions).takeIf { "question" in supports },
            )
            if (labels.isNotEmpty()) Text(labels.joinToString(" · "), style = MaterialTheme.typography.labelSmall)
            ResultNoteLink(source.string("title"), source.string("passage")) { onNote(source.string("id")) }
        }
        if (candidate == null && (answer != null || error.isNotBlank())) {
            val hasDraft = answer?.get("draft") is JsonObject
            Button(enabled = !busy && online, onClick = { prepare() }) {
                    Text(stringResource(when {
                        noteId != null -> if (hasDraft) R.string.decision_review_outcome else R.string.decision_record_outcome
                        hasDraft -> R.string.decision_review_distinct
                        else -> R.string.decision_use_wording
                    }))
                }
        }
        candidate?.let { output ->
            updateTitle?.let { Text(stringResource(R.string.decision_update_match, it)) }
            val openSaved = if (output.string("command_id") == "record-decision" &&
                (output["payload"] as? JsonObject)?.string("type") == "notes") onCreated else onNote
            RenderOutput(model, output, openSaved, {
                candidate = it
                if ((it["payload"] as? JsonObject)?.string("type") == "notes") onSaved()
            }, commandScope = scope)
            TextButton(onClick = { candidate = null; updateTitle = null }) {
                Text(stringResource(R.string.decision_back_to_wording))
            }
        }
        if (busy) LinearProgressIndicator()
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
    }
}

private fun applyDecisionDraft(command: String, noteId: String?, schema: JsonObject, draft: JsonObject, sources: List<JsonObject>): JsonObject {
    val fields = schema["properties"] as? JsonObject ?: JsonObject(emptyMap())
    val properties = JsonObject(fields.mapValues { (key, field) ->
        val value = draft[key]
        if (value is JsonPrimitive && value.isString && field is JsonObject) JsonObject(field + ("default" to value))
        else field
    })
    return commandEnvelope(DecisionJournalId, command, buildJsonObject {
        noteId?.let { put("note_id", it) }
        if (sources.isNotEmpty()) put("evidence", buildJsonObject {
            sources.forEach { source ->
                put(source.string("id"), buildJsonObject {
                    put("version", source.getValue("version"))
                    put("fingerprint", source.getValue("fingerprint"))
                })
            }
        })
    }, buildJsonObject {
        put("type", "form"); put("schema", JsonObject(schema + ("properties" to properties)))
    })
}
