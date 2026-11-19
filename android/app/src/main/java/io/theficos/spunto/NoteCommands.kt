@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package io.theficos.spunto

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.theficos.spunto.core.plugins.objects
import io.theficos.spunto.core.design.SpuntoSpacing
import io.theficos.spunto.core.model.Note
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Stable
internal class NoteToolRunner(private val model: SpuntoViewModel, private val noteId: String, private val scope: CoroutineScope) {
    var running by mutableStateOf(false)
        private set
    var error by mutableStateOf("")
        private set

    fun run(tool: NoteTool, manifest: JsonObject, onLink: () -> Boolean, onResult: (JsonObject) -> Unit) {
        if (running || !model.workspaceIsOwner.value || !model.online.value) return
        val resources = model.container.context.resources
        error = ""
        if (tool.bundled && tool.pluginId == "links" && tool.id == "link") {
            if (!supportsNotePicker(manifest, "links")) error = resources.getString(R.string.core_link_upgrade_server)
            else if (!onLink()) error = resources.getString(R.string.settings_this_command_is_unavailable)
            return
        }
        running = true
        runCommand(model, tool.pluginId, tool.id, buildJsonObject { put("note_id", noteId) },
            onResult, { running = false }, scope, onError = { error = it }, requireOwner = true)
    }
}

@Composable
internal fun rememberNoteToolRunner(model: SpuntoViewModel, noteId: String): NoteToolRunner = key(model, noteId) {
    val scope = rememberCoroutineScope()
    remember { NoteToolRunner(model, noteId, scope) }
}

@Composable
internal fun NoteCommands(
    model: SpuntoViewModel, note: Note, detail: NoteToolDetail?, runner: NoteToolRunner,
    shortcuts: Boolean = false, tidyShortcutFocus: FocusRequester? = null, onLink: () -> Boolean, onResult: (JsonObject) -> Unit,
) {
    val catalog by model.catalog.collectAsState()
    val manifest by model.manifest.collectAsState()
    val owner by model.workspaceIsOwner.collectAsState()
    if (!owner) return
    val toolDetail = detail ?: if (!note.privateNote && !note.machine) rememberNoteToolDetail(model, note) else NoteToolDetail()
    val resources = model.container.context.resources
    val locale = resources.configuration.locales[0]
    val running = runner.running
    val error = runner.error
    val legacy = toolDetail.legacyScope
    val inputs = if (legacy == null) remember(toolDetail.data) { parseNoteToolInputs(toolDetail.data) } else {
        val attachments by remember(note.id, model) { model.repository.dao.attachments(note.id) }.collectAsState(emptyList())
        remember(note, legacy, attachments) { legacyNoteToolInputs(note, legacy, attachments) }
    }
    val commands = remember(manifest, catalog, note, inputs, locale) {
        noteTools(
            resources, manifest.objects("plugins"), note.privateNote || note.machine, catalog,
            versionedAgentReview = supportsVersionedAgentReview(manifest),
            legacyAgentReview = note.sourceKind == "agent" || note.updatedViaAgent,
            inputs = inputs,
        )
    }
    val waiting = remember(catalog, manifest, inputs, locale, note.privateNote, note.machine, shortcuts) {
        if (note.privateNote || note.machine || shortcuts) emptyList() else {
            val candidates = catalog.objects("plugins").map { NativePluginFacts(it) }.filter { it.wantedOn && it.state == "waiting" }
            val toolPlugins = noteTools(resources, candidates.map { it.raw }, catalog = catalog,
                versionedAgentReview = supportsVersionedAgentReview(manifest), inputs = inputs, waitingOnly = true)
                .map { it.pluginId }.toSet()
            candidates.filter { it.id in toolPlugins }
        }
    }
    val shown = when {
        !shortcuts -> commands
        toolDetail.loading && !note.privateNote && !note.machine && needsNoteShortcutInputs(manifest, catalog) -> emptyList()
        else -> automaticNoteTools(commands, inputs, supportsNotePicker(manifest, "links"))
    }
    if (shown.isEmpty() && waiting.isEmpty() && !running && error.isBlank()) return

    Column(if (shortcuts) Modifier else Modifier.padding(horizontal = SpuntoSpacing.s20),
        verticalArrangement = Arrangement.spacedBy(SpuntoSpacing.s8)) {
        if (!shortcuts) Text(resources.getString(R.string.settings_note_tools), style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.semantics { heading() })
        NoteActionGrid {
            shown.forEach { tool ->
                key(tool.pluginId, tool.id) {
                    val tile = Modifier.weight(1f)
                    val target = tidyShortcutFocus?.takeIf { tool.bundled && tool.pluginId == "tidy" && tool.id == "tidy" }
                    NoteActionTile(tool.icon, tool.title, if (target != null) tile.focusRequester(target) else tile, enabled = !running,
                        onClick = { runner.run(tool, manifest, onLink, onResult) })
                }
            }
        }
        if (waiting.isNotEmpty()) {
            waiting.forEach { plugin ->
                Text(localizedPluginName(resources, plugin.raw, catalog), style = MaterialTheme.typography.titleSmall)
                Text(pluginWaitingLine(resources, plugin), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { model.pluginWeb("/settings/ai") }) { Text(resources.getString(R.string.settings_ai_settings)) }
        }
        if (running) LinearProgressIndicator(Modifier.fillMaxWidth())
        val status = error.ifBlank { if (running) resources.getString(R.string.plugin_working) else "" }
        if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall,
            color = if (error.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    }
}

@Composable
internal fun NoteActionGrid(content: @Composable FlowRowScope.() -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(SpuntoSpacing.s8),
            verticalArrangement = Arrangement.spacedBy(SpuntoSpacing.s8),
            maxItemsInEachRow = if (maxWidth < 264.dp || LocalDensity.current.fontScale >= 1.5f) 1 else 2,
            content = content,
        )
    }
}

@Composable
internal fun NoteActionTile(icon: Int, label: String, modifier: Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 52.dp).semantics { role = Role.Button },
        color = colors.surfaceContainerHigh, contentColor = colors.onSurface, shape = MaterialTheme.shapes.medium,
    ) {
        Row(Modifier.padding(horizontal = SpuntoSpacing.s12, vertical = SpuntoSpacing.s12),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(SpuntoSpacing.s8)) {
            Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(20.dp),
                tint = if (enabled) colors.primary else colors.onSurface.copy(alpha = .38f))
            Text(label, style = MaterialTheme.typography.labelLarge,
                color = if (enabled) colors.onSurface else colors.onSurface.copy(alpha = .38f))
        }
    }
}
