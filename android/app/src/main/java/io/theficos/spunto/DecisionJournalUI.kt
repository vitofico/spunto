@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package io.theficos.spunto

import android.content.res.Resources
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.theficos.spunto.core.design.SpuntoSpacing
import io.theficos.spunto.core.model.Note
import io.theficos.spunto.core.plugins.*
import io.theficos.spunto.data.sync.SyncStatus
import kotlinx.serialization.json.*
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.FormatStyle
import java.time.format.ResolverStyle
import java.util.Locale

internal const val DecisionJournalId = "decision-journal"
internal const val DecisionViewRoute = "view:decision-journal:journal"

internal fun journalResult(result: JsonObject) = result.string("plugin_id") == DecisionJournalId

internal fun isJournalNote(note: Note) = !note.trashed && note.conflictOf == null &&
    note.sourceKind == "plugin" && note.sourceRef == DecisionJournalId

@Composable
internal fun DecisionRecordAction(model: SpuntoViewModel) {
    DecisionActions(model, null, listOf("record-decision" to R.string.settings_bundled_title_12))
}

@Composable
internal fun DecisionNoteActions(model: SpuntoViewModel, note: Note) {
    DecisionActions(model, note.id, listOf(
        "review-decision" to R.string.settings_bundled_title_13,
        "journal" to R.string.decision_outcomes,
    ))
}

internal fun decisionActionIds(manifest: JsonObject, owner: Boolean, online: Boolean): Set<String> {
    if (!owner || !online) return emptySet()
    val plugin = manifest.objects("plugins").firstOrNull {
        it.string("id") == DecisionJournalId && it.bool("enabled", true) &&
            it.string("state") !in setOf("disabled", "off", "failed", "waiting", "removed")
    }
    return (plugin?.get("contributes") as? JsonObject)?.objects("commands").orEmpty()
        .map { it.string("id") }.toSet()
}

@Composable
private fun DecisionActions(model: SpuntoViewModel, noteId: String?, actions: List<Pair<String, Int>>) {
    val owner by model.workspaceIsOwner.collectAsState()
    val online by model.online.collectAsState()
    val manifest by model.manifest.collectAsState()
    val commands = decisionActionIds(manifest, owner, online)
    if (commands.isEmpty()) return
    var running by remember(noteId) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    FlowRow(horizontalArrangement = Arrangement.spacedBy(SpuntoSpacing.s12)) {
        actions.filter { (id, _) -> id in commands }.forEach { (id, title) ->
            TextButton(enabled = !running, modifier = Modifier.heightIn(min = 48.dp), contentPadding = PaddingValues(0.dp), onClick = {
                if (!running) {
                    running = true
                    val args = buildJsonObject { noteId?.let { put("note_id", it) } }
                    runCommand(model, DecisionJournalId, id, args, { model.remote.value = it }, { running = false }, scope)
                }
            }) { Text(stringResource(title)) }
        }
    }
    if (running) Text(stringResource(R.string.decision_opening), style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
}

private val decisionDetailFields = setOf("title", "rationale", "alternatives", "expected", "questions")
private val journalFieldTitles = mapOf(
    "title" to R.string.decision_title,
    "decision" to R.string.decision_choice,
    "alternatives" to R.string.decision_alternatives,
    "rationale" to R.string.decision_reason,
    "expected" to R.string.decision_expected,
    "questions" to R.string.decision_questions,
    "private" to R.string.core_private,
    "outcome" to R.string.decision_actual,
    "reflection" to R.string.decision_reflection,
    "status" to R.string.decision_status,
)

internal fun validDecisionDate(value: String): Boolean = value.isEmpty() ||
    try {
        val date = LocalDate.parse(value)
        date.year in 1..9999 && date.toString() == value
    } catch (_: DateTimeParseException) {
        false
    }

private val journalStatuses = mapOf(
    "Open" to R.string.decision_status_open,
    "Reviewed" to R.string.decision_reviewed,
    "Closed" to R.string.decision_closed,
    "Reopened" to R.string.decision_status_reopened,
)
private val scheduledReview = Regex("""(Open|Reviewed) · Review (on|due) (\d{4}-\d{2}-\d{2})""")
private val outcomeHistory = Regex("""(Reopened|Reviewed|Closed) · (\d{1,2} [A-Za-z]+ \d{4}, \d{2}:\d{2} UTC)""")
private val serverHistoryDate = DateTimeFormatter.ofPattern("d MMMM uuuu, HH:mm 'UTC'", Locale.ENGLISH)
    .withResolverStyle(ResolverStyle.STRICT)

/** This recognizes the journal command's summary and outcome labels. Note content is rendered separately. */
internal fun localizedJournalLabel(resources: Resources, value: String): String {
    journalStatuses[value]?.let { return resources.getString(it) }
    val locale = resources.configuration.locales[0]
    scheduledReview.matchEntire(value)?.destructured?.let { (status, timing, date) ->
        if (!validDecisionDate(date)) return value
        val displayed = LocalDate.parse(date).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
        val dateLabel = resources.getString(if (timing == "due") R.string.decision_review_due else R.string.decision_review_on, displayed)
        return "${resources.getString(journalStatuses.getValue(status))} · $dateLabel"
    }
    outcomeHistory.matchEntire(value)?.destructured?.let { (status, stamp) ->
        val date = try { LocalDateTime.parse(stamp, serverHistoryDate) } catch (_: DateTimeParseException) { return value }
        val displayed = date.format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale))
        return "${resources.getString(journalStatuses.getValue(status))} · $displayed UTC"
    }
    return value
}

@Composable
internal fun DecisionForm(schema: JsonObject, command: String, initial: JsonObject, online: Boolean, running: Boolean, onSave: (JsonObject) -> Unit) {
    val reviewing = command == "review-decision"
    val dateTitle = stringResource(if (reviewing) R.string.decision_next_review else R.string.decision_review_date)
    val dateHint = stringResource(R.string.decision_date_hint)
    val privacyHint = stringResource(R.string.decision_privacy_hint)
    val required = (schema["required"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content }.toSet()
    val fields = (schema["properties"] as? JsonObject).orEmpty()
    val primary = if (reviewing) emptyMap() else listOf("decision", "review_on", "private")
        .mapNotNull { key -> fields[key]?.let { key to it } }.toMap()
    val localized = JsonObject(schema + ("properties" to buildJsonObject {
        (primary + fields).forEach { (name, value) ->
            val field = value.jsonObject
            val title = if (name == "review_on") dateTitle else journalFieldTitles[name]?.let {
                val label = stringResource(it)
                if (name !in required && name in decisionDetailFields) {
                    stringResource(R.string.decision_optional_field, label)
                } else label
            }
            val description = when (name) {
                "review_on" -> dateHint
                "private" -> privacyHint
                else -> null
            }
            put(name, buildJsonObject {
                field.forEach { (key, element) -> put(key, element) }
                title?.let { put("title", it) }
                description?.let { put("description", it) }
            })
        }
    }))
    val inheritedPrivacy = !reviewing && initial.string("note_id").isNotBlank() &&
        ((schema["properties"] as? JsonObject)?.get("private") as? JsonObject)?.get("default") == JsonPrimitive(true)
    val dateError = stringResource(R.string.decision_invalid_date)
    val statusLabels = mapOf(
        "open" to stringResource(R.string.decision_open),
        "reviewed" to stringResource(R.string.decision_reviewed),
        "closed" to stringResource(R.string.decision_closed),
    )
    if (inheritedPrivacy) {
        Text(stringResource(R.string.decision_inherited_privacy), style = MaterialTheme.typography.bodySmall)
        Text(privacyHint, style = MaterialTheme.typography.bodySmall)
    }
    if (!online) Text(stringResource(R.string.decision_connect_to_save), style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    SchemaForm(
        localized, initial, readOnly = running, readOnlyMessage = "", preferDefaults = true,
        saveLabel = stringResource(when (command) {
            "review-decision" -> R.string.decision_record_outcome
            "journal" -> R.string.decision_save_schedule
            else -> R.string.decision_save
        }),
        hiddenFields = commandFormHiddenFields(schema) + if (inheritedPrivacy) setOf("private") else emptySet(),
        submitEnabled = online, retainEdits = true,
        choiceLabel = { field, value -> if (field == "status") statusLabels[value.jsonPrimitive.content] else null },
        invalidInputMessage = stringResource(R.string.decision_check_fields),
        validateInput = { args -> dateError.takeUnless { validDecisionDate(args.string("review_on").trim()) } },
        detailsFields = if (reviewing || "evidence" in initial) emptySet() else decisionDetailFields - required,
        detailsLabels = stringResource(R.string.decision_add_details) to stringResource(R.string.decision_hide_details),
        onSave = onSave,
    )
}

@Composable
internal fun DecisionSavedOutput(model: SpuntoViewModel, ids: JsonArray, reviewing: Boolean, onNote: (String) -> Unit) {
    val noteIds = remember(ids) { ids.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } }
    if (noteIds.isEmpty()) return
    val notes by remember(noteIds) { model.repository.observeAll(noteIds) }.collectAsState(null)
    val available = notes?.filterNot { it.trashed }?.map { it.id }.orEmpty()
    Text(stringResource(if (reviewing) R.string.decision_outcome_saved else R.string.decision_saved),
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    noteIds.forEachIndexed { index, id ->
        val note = notes?.firstOrNull { it.id == id && !it.trashed } ?: return@forEachIndexed
        ResultNoteLink(stringResource(if (reviewing && index == 0) R.string.decision_open_outcome else R.string.decision_open_original), note.title) { onNote(id) }
    }

    if (notes != null && available.size < noteIds.size) {
        Text(stringResource(R.string.decision_saved_sync), style = MaterialTheme.typography.bodySmall)
        DecisionSyncAction(model)
    }
}

@Composable
internal fun DecisionSyncAction(model: SpuntoViewModel) {
    val connected by model.container.connected.collectAsState()
    val sync by model.status.collectAsState()
    if (connected && sync != SyncStatus.NeedsSignIn) {
        TextButton(onClick = { model.synchronize() }, enabled = sync != SyncStatus.Running) {
            Text(stringResource(R.string.decision_sync))
        }
    }
}
