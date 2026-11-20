package io.theficos.spunto

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import io.theficos.spunto.core.design.SpuntoSpacing
import io.theficos.spunto.core.plugins.bool
import io.theficos.spunto.core.plugins.objects
import io.theficos.spunto.core.plugins.outputComponents
import io.theficos.spunto.core.plugins.string
import io.theficos.spunto.data.sync.ApiException
import io.theficos.spunto.data.sync.AuthConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import java.util.UUID

internal const val CollectionsRoute = "collections"

internal fun collectionsAvailable(manifest: JsonObject): Boolean {
    val entry = manifest.objects("plugins").singleOrNull { it.string("id") == "context-packs" } ?: return false
    return collectionsPluginSupported(entry)
}

internal fun collectionsPluginSupported(entry: JsonObject): Boolean {
    val (major, minor) = entry.string("version").split('.').let {
        (it.firstOrNull()?.toIntOrNull() ?: 0) to (it.getOrNull(1)?.toIntOrNull() ?: 0)
    }
    return entry.bool("enabled", true) && entry.string("state") in setOf("", "enabled") &&
        entry.string("origin") != "operator" && entry.string("source_kind") !in setOf("folder", "entrypoint") &&
        (major > 1 || (major == 1 && minor >= 2))
}

private data class CollectionSource(
    val id: String,
    val title: String,
    val version: Long?,
    val pinned: Boolean,
    val status: String = "current",
    val accepted: Boolean = false,
) {
    fun args() = buildJsonObject {
        put("id", id)
        put("pinned", pinned)
        if (accepted && version != null) put("version", version)
    }
}

private fun JsonObject.toSource(accepted: Boolean = false) = CollectionSource(
    string("id"), string("title"), pluginLong("version"), bool("pinned"), string("status").ifBlank { "current" }, accepted,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun CollectionsScreen(model: SpuntoViewModel, route: String, onNote: (String) -> Unit) {
    val auth by model.auth.collectAsState()
    val epoch by model.pluginReplicaEpochState.collectAsState()
    val canManage by model.workspaceIsOwner.collectAsState()
    val manifest by model.manifest.collectAsState()
    val online by model.online.collectAsState()
    val lock by model.container.appLock.state.collectAsState()
    if (!online || !canManage || !lock.unlocked || !collectionsAvailable(manifest) || auth == null) {
        Text(stringResource(
            if (!online) R.string.collections_connect else R.string.settings_this_plugin_is_unavailable_sync_to_refresh
        ), Modifier.padding(SpuntoSpacing.s20))
        return
    }
    key(auth, epoch, route) {
        ActiveCollections(model, route, onNote, requireNotNull(auth), epoch, lock.revision)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun ActiveCollections(
    model: SpuntoViewModel,
    route: String,
    onNote: (String) -> Unit,
    auth: AuthConfig,
    epoch: String,
    locked: Long,
) {
    val resources = LocalContext.current.resources
    val scope = rememberCoroutineScope()
    val nav = LocalNavigator.current
    val feedback = LocalFeedback.current
    val clipboard = LocalClipboardManager.current
    val manifest by model.manifest.collectAsState()
    val parts = route.split(':', limit = 3)
    val id = parts.getOrNull(1)?.takeIf { it !in setOf("add", "new") }
    val seedId = parts.getOrNull(2)
    val library = id == null && parts.getOrNull(1) != "new"
    val ai = manifest.objects("plugins").any { it.string("id") == "note-assist" && it.string("origin") != "operator" }
    val ask = libraryCommandAvailable(manifest, "ask", "ask")
    val ready: () -> Boolean = {
        model.online.value && model.auth.value == auth && model.pluginReplicaEpoch == epoch && model.workspaceIsOwner.value &&
            model.container.appLock.state.value.let { it.unlocked && it.revision == locked } &&
            collectionsAvailable(model.container.manifest.value)
    }
    val api = remember(auth, epoch, locked) { pluginApi(auth, resources).guarded { ready() } }
    val requestId = remember { UUID.randomUUID().toString() }
    var listSearch by remember { mutableStateOf("") }
    var showAccess by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var after by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf<JsonObject?>(null) }
    var editing by remember { mutableStateOf(id == null || seedId != null) }
    var showUpdates by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    var intro by remember { mutableStateOf("") }
    var introProof by remember { mutableStateOf(JsonObject(emptyMap())) }
    var assistGoal by remember { mutableStateOf("") }
    var snapshotNote by remember { mutableStateOf<String?>(null) }
    var snapshotMessage by remember { mutableStateOf("") }
    var goal by remember { mutableStateOf("") }
    var instruction by remember { mutableStateOf("") }
    var restricted by remember { mutableStateOf(true) }
    var external by remember { mutableStateOf(false) }
    var sources by remember { mutableStateOf<List<CollectionSource>>(emptyList()) }
    var proposal by remember { mutableStateOf<JsonObject?>(null) }
    var delta by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var mode by remember { mutableStateOf("draft") }
    var error by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var begun by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var showBrief by remember { mutableStateOf(false) }
    var dirty by remember { mutableStateOf(false) }
    fun loadDetail(result: JsonObject) {
        saved = result
        title = result.string("title")
        intro = result.string("intro")
        introProof = result["intro_citations"] as? JsonObject ?: JsonObject(emptyMap())
        snapshotNote = null
        snapshotMessage = ""
        goal = result.string("goal")
        restricted = result.bool("private")
        external = result.bool("external")
        sources = result.objects("sources").map { it.toSource() }
        dirty = result.bool("draft")
        editing = dirty || seedId != null
    }
    fun choose(entry: CollectionSource) {
        if (sources.none { it.id == entry.id } && sources.size >= 10) return
        sources = if (sources.any { it.id == entry.id }) {
            sources.map { if (it.id == entry.id) entry.copy(pinned = it.pinned) else it }
        } else sources + entry
        dirty = true
        editing = true
    }
    suspend fun post(path: String, args: JsonObject): JsonObject = api.request(path, "POST", args) as JsonObject
    fun launch(work: suspend () -> Unit) {
        if (loading || !ready()) return
        loading = true
        error = ""
        scope.launch {
            try {
                work()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (!ready()) return@launch
                if (failure is ApiException && failure.status == 401) model.reauthenticate.value = true
                error = if (failure is ApiException) pluginApiError(resources, failure) else resources.getString(R.string.sys_server_unreachable)
            } finally {
                loading = false
            }
        }
    }
    fun more(cursor: String? = null) = launch {
        val result = post("/api/v1/collections", buildJsonObject { put("query", listSearch); cursor?.let { put("cursor", it) } })
        if (ready()) {
            page = if (cursor == null) result.objects("collections") else (page + result.objects("collections")).distinctBy { it.string("id") }
            after = result.string("next_cursor").takeIf { it.isNotBlank() }
            begun = true
        }
    }
    fun initialize() {
        if (library) more()
        else launch {
            if (id != null) {
                val result = post("/api/v1/collections", buildJsonObject { put("action", "read"); put("id", id) })
                if (ready()) loadDetail(result)
            }
            if (seedId != null && sources.none { it.id == seedId }) {
                val result = api.get("/api/v1/notes/$seedId")
                if (ready()) choose(result.toSource(accepted = true))
            }
            begun = true
        }
    }
    LaunchedEffect(Unit) { initialize() }

    fun apply(change: () -> Unit) { change(); dirty = true; editing = true }
    fun move(index: Int, step: Int) = apply {
        sources = sources.toMutableList().apply { add(index + step, removeAt(index)) }
    }
    fun save() = launch {
        val result = post("/api/v1/collections", buildJsonObject {
            put("action", "save")
            put("id", saved?.string("id") ?: requestId)
            saved?.pluginLong("revision")?.let { put("revision", it) }
            put("title", title)
            put("intro", intro)
            put("intro_citations", if (intro.isBlank()) JsonObject(emptyMap()) else introProof)
            put("goal", goal)
            put("private", restricted)
            put("external", external)
            put("sources", JsonArray(sources.map { it.args() }))
        })
        if (ready()) {
            loadDetail(result)
            editing = false
            showUpdates = false
            if (id == null || seedId != null) nav.replaceTop("$CollectionsRoute:${result.string("id")}")
            feedback.show(resources.getString(R.string.collections_saved))
        }
    }
    val privateGoal = saved?.let { detail ->
        detail.bool("private") || detail.bool("external") || detail.string("goal").isBlank() ||
            detail.objects("sources").any { it.bool("private") || it.bool("external") || it.string("status") == "unavailable" }
    } == true
    val requestGoal = if (privateGoal) assistGoal else goal
    fun introductionAvailable(result: JsonObject): Boolean {
        val proof = result["fingerprints"] as? JsonObject ?: return false
        val proposals = result.objects("sources")
        return proof.isNotEmpty() && proof.keys.all { key ->
            sources.any { source ->
                source.id == key && source.status == "current" &&
                    proposals.any { it.string("id") == key && it.pluginLong("version") == source.version }
            }
        }
    }
    fun applyIntroduction(result: JsonObject) {
        if (introductionAvailable(result)) apply {
            intro = result.string("intro")
            introProof = result["fingerprints"] as JsonObject
        }
    }
    fun assist(requested: String) = launch {
        mode = requested
        val result = post("/api/v1/note-assist/collections", buildJsonObject {
            put("mode", requested)
            put("goal", requestGoal)
            put("instruction", instruction)
            put("selected", JsonArray(sources.map { JsonPrimitive(it.id) }))
            proposal?.objects("sources")?.let { put("proposed", JsonArray(it.map { hit -> JsonPrimitive(hit.string("id")) })) }
            saved?.let { put("collection_id", it.string("id")) }
        })
        if (ready()) {
            delta = proposal?.takeIf { instruction.isNotBlank() }?.let { prior ->
                val previousIds = prior.objects("sources").map { it.string("id") }.toSet()
                val nextIds = result.objects("sources").map { it.string("id") }.toSet()
                (nextIds - previousIds).size to (previousIds - nextIds).size
            }
            proposal = result
        }
    }

    val assistantPanel: @Composable () -> Unit = {
        if (ai) {
            Text(stringResource(R.string.collections_model_guidance))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(SpuntoSpacing.s8)) {
                TextButton(onClick = { assist(if (editing) "draft" else "updates") }, enabled = requestGoal.isNotBlank() && !loading) { Text(stringResource(if (editing) R.string.collections_find_notes else R.string.collections_recent_search)) }
                if (editing && saved != null && saved?.bool("draft") == false) TextButton(onClick = { assist("updates") }, enabled = requestGoal.isNotBlank() && !loading) { Text(stringResource(R.string.collections_whats_new)) }
            }
            delta?.let { (added, removed) ->
                Text(stringResource(R.string.collections_delta, added, removed),
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            proposal?.let { result ->
                val matches = result.objects("sources")
                if (matches.isEmpty()) Text(stringResource(R.string.collections_no_matches))
                val citations = (result["citations"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content }
                for (match in matches) {
                    val candidate = match.toSource(accepted = true).copy(status = "current")
                    val linked = sources.find { it.id == candidate.id }
                    val included = linked != null && linked.version == candidate.version && linked.status == "current"
                    Text(candidate.title.ifBlank { stringResource(R.string.collections_untitled) }, style = MaterialTheme.typography.titleMedium)
                    if (candidate.id in citations) Text(stringResource(R.string.collections_cited))
                    Text(match.string("passage"), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { choose(candidate) },
                        enabled = !included && !loading && (linked != null || sources.size < 10)) {
                        Text(stringResource(if (included) R.string.collections_included else if (linked != null) R.string.collections_accept_update else R.string.collections_add_note))
                    }
                }
                if (matches.isNotEmpty()) Button(onClick = { apply {
                    if (title.isBlank()) title = result.string("title").ifBlank { requestGoal.trim().take(80) }
                    matches.filterNot { match -> sources.any { it.id == match.string("id") } }
                        .take(10 - sources.size).forEach { choose(it.toSource(accepted = true).copy(status = "current")) }
                    if (intro.isBlank()) applyIntroduction(result)
                } }, enabled = !loading) { Text(stringResource(R.string.collections_use_suggestions)) }
                result.string("intro").takeIf(String::isNotBlank)?.let { draft ->
                    Text(draft)
                    TextButton(onClick = { applyIntroduction(result) }, enabled = !loading && introductionAvailable(result)) { Text(stringResource(R.string.collections_use_intro)) }
                }
                OutlinedTextField(instruction, { instruction = it.take(500) }, Modifier.fillMaxWidth(), enabled = !loading,
                    label = { Text(stringResource(R.string.collections_refine)) })
                TextButton(onClick = { assist(mode) }, enabled = requestGoal.isNotBlank() && instruction.isNotBlank() && !loading) { Text(stringResource(R.string.collections_refine_action)) }
            }
        } else {
            Text(stringResource(R.string.collections_ai_off))
            TextButton(onClick = { nav.open("plugin:note-assist") }) { Text(stringResource(R.string.collections_assist_settings)) }
        }

    }
    val briefPanel: @Composable () -> Unit = {
        if (showBrief && saved != null && !dirty) {
            val detail = saved ?: error("Missing saved collection")
            Text(detail.string("agent_access"))
            Text(detail.string("markdown"))
            TextButton(onClick = {
                clipboard.setText(AnnotatedString(detail.string("markdown")))
                feedback.show(resources.getString(R.string.core_copied))
            }) { Text(stringResource(R.string.core_copy)) }
            TextButton(enabled = !loading && sources.all { it.status == "current" },onClick = {
                launch {
                    val result = post("/api/v1/plugins/context-packs/commands/edit",buildJsonObject {
                        put("action","save-note")
                        put("pack_id",detail.string("id"))
                        put("expected_revision",detail.pluginLong("revision"))
                    })
                    if (ready()) {
                        val components = outputComponents(result)
                        snapshotNote = components.firstOrNull { it.string("type") == "notes" }
                            ?.get("ids")?.let { it as? JsonArray }?.firstOrNull()?.jsonPrimitive?.content
                        snapshotMessage = components.firstOrNull { it.string("type") == "text" }?.string("text").orEmpty()
                    }
                }
            }) { Text(stringResource(R.string.collections_save_snapshot)) }
            if (snapshotMessage.isNotBlank()) Text(snapshotMessage, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            snapshotNote?.let { noteId ->
                TextButton(onClick = { onNote(noteId) }) { Text(stringResource(R.string.collections_open_source)) }
            }
        }
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(SpuntoSpacing.s20),
        verticalArrangement = Arrangement.spacedBy(SpuntoSpacing.s12),
    ) {
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (error.isNotBlank()) {
            Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            if (!begun) TextButton(onClick = ::initialize) { Text(stringResource(R.string.core_ask_retry)) }
        }
        if (library) {
            Text(stringResource(if (seedId == null) R.string.collections_intro else R.string.collections_choose_collection))
            Button(onClick = { nav.open("$CollectionsRoute:new" + (seedId?.let { ":$it" } ?: "")) }) {
                Text(stringResource(R.string.collections_new))
            }
            OutlinedTextField(listSearch, { listSearch = it.take(80) }, Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.collections_search)) }, enabled = !loading)
            TextButton(onClick = { more() }, enabled = !loading) { Text(stringResource(R.string.collections_search)) }
            if (begun && page.isEmpty()) Text(stringResource(R.string.collections_empty))
            for (entry in page) OutlinedCard(
                onClick = { nav.open("$CollectionsRoute:${entry.string("id")}" + (seedId?.let { ":$it" } ?: "")) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Column(Modifier.padding(SpuntoSpacing.s16)) {
                    Text(entry.string("title"), style = MaterialTheme.typography.titleMedium)
                    entry.pluginLong("updated_at")?.let {
                        Text(java.text.DateFormat.getDateInstance().format(java.util.Date(it)), style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        if (entry.bool("draft")) stringResource(R.string.collections_draft)
                        else stringResource(R.string.collections_source_count, entry.pluginLong("count") ?: 0),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            after?.let { cursor ->
                TextButton(onClick = { more(cursor) }, enabled = !loading) { Text(stringResource(R.string.collections_more)) }
            }
        } else if (begun && !editing && saved != null) {
            val detail = requireNotNull(saved)
            Text(detail.string("title"), style = MaterialTheme.typography.headlineSmall)
            detail.string("intro").ifBlank { detail.string("goal") }.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
            Text(stringResource(R.string.collections_sources), style = MaterialTheme.typography.titleLarge)
            if (sources.isEmpty()) Text(stringResource(R.string.collections_no_sources))
            for (item in sources) {
                if (item.status == "unavailable") Text(stringResource(R.string.collections_unavailable))
                else TextButton(onClick = { onNote(item.id) }) { Text(item.title.ifBlank { stringResource(R.string.collections_untitled) }) }
                if (item.pinned) Text(stringResource(R.string.collections_pinned))
                if (item.status == "changed") Text(stringResource(R.string.collections_changed))
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(SpuntoSpacing.s8)) {
                Button(onClick = { editing = true }) { Text(stringResource(R.string.collections_edit)) }
                TextButton(onClick = { showUpdates = !showUpdates }) { Text(stringResource(R.string.collections_whats_new)) }
                if (ask && detail.bool("ask_ready")) TextButton(onClick = { nav.open("ask:${detail.string("id")}") }) {
                    Text(stringResource(R.string.collections_discussion))
                }
                TextButton(onClick = { showBrief = !showBrief }) { Text(stringResource(R.string.collections_use_brief)) }
            }
            if (ask) Text(stringResource(if (detail.bool("ask_ready")) R.string.collections_ask_guidance else R.string.collections_ask_ineligible))
            if (showUpdates) {
                if (privateGoal) OutlinedTextField(assistGoal, { assistGoal = it.take(500) }, Modifier.fillMaxWidth(), enabled = !loading,
                    label = { Text(stringResource(R.string.collections_assist_goal)) }, minLines = 2)
                else if (goal.isNotBlank()) Text(goal)
                assistantPanel()
            }
            briefPanel()
        } else if (begun) {
            Text(saved?.string("title") ?: stringResource(R.string.collections_new), style = MaterialTheme.typography.headlineSmall)
            if (privateGoal) {
                OutlinedTextField(assistGoal, { assistGoal = it.take(500) }, Modifier.fillMaxWidth(), enabled = !loading,
                    label = { Text(stringResource(R.string.collections_assist_goal)) }, minLines = 2)
            } else {
                OutlinedTextField(goal, { apply { goal = it.take(500) } }, Modifier.fillMaxWidth(), enabled = !loading,
                    label = { Text(stringResource(R.string.collections_goal)) }, minLines = 2)
            }
            HorizontalDivider()
            assistantPanel()
            if (privateGoal) {
                OutlinedTextField(goal, { apply { goal = it.take(500) } }, Modifier.fillMaxWidth(), enabled = !loading,
                    label = { Text(stringResource(R.string.collections_goal)) }, minLines = 2)
            }
            OutlinedTextField(title, { apply { title = it.take(80) } }, Modifier.fillMaxWidth(), enabled = !loading,
                label = { Text(stringResource(R.string.collections_name)) })
            OutlinedTextField(intro, { apply {
                intro = it.take(600)
                if (intro.isBlank()) introProof = JsonObject(emptyMap())
            } }, Modifier.fillMaxWidth(), enabled = !loading,
                label = { Text(stringResource(R.string.collections_brief)) }, minLines = 3)
            TextButton(onClick = { showAccess = !showAccess }) { Text(stringResource(R.string.collections_access)) }
            if (showAccess) {
                Row { Checkbox(restricted, { apply { restricted = it } }, enabled = !loading); Text(stringResource(R.string.collections_private), Modifier.padding(top = SpuntoSpacing.s12)) }
                Row { Checkbox(external, { apply { external = it } }, enabled = !loading); Text(stringResource(R.string.collections_external), Modifier.padding(top = SpuntoSpacing.s12)) }
            }
            HorizontalDivider()
            Text(stringResource(R.string.collections_sources), style = MaterialTheme.typography.titleLarge)
            if (sources.isEmpty()) Text(stringResource(R.string.collections_no_sources))
            for ((index, item) in sources.withIndex()) {
                Text(item.title.ifBlank { stringResource(R.string.collections_unavailable) }, style = MaterialTheme.typography.titleMedium)
                if (item.pinned) Text(stringResource(R.string.collections_pinned))
                if (item.status != "current") Text(stringResource(if (item.status == "changed") R.string.collections_changed else R.string.collections_unavailable))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(SpuntoSpacing.s8)) {
                    if (item.status != "unavailable") TextButton(onClick = { onNote(item.id) }) { Text(stringResource(R.string.collections_open_source)) }
                    TextButton(onClick = { apply { sources = sources.toMutableList().apply { this[index] = item.copy(pinned = !item.pinned) } } }, enabled = !loading) {
                        Text(stringResource(if (item.pinned) R.string.collections_unpin else R.string.collections_pin))
                    }
                    TextButton(onClick = { move(index, -1) }, enabled = index > 0 && !loading) { Text(stringResource(R.string.collections_up)) }
                    TextButton(onClick = { move(index, 1) }, enabled = index < sources.lastIndex && !loading) { Text(stringResource(R.string.collections_down)) }
                    if (item.status == "changed" && item.version != null) TextButton(onClick = { choose(item.copy(status = "current", accepted = true)) }, enabled = !loading) { Text(stringResource(R.string.collections_accept_update)) }
                    TextButton(onClick = { apply { sources = sources.filterNot { it.id == item.id } } }, enabled = !loading && (intro.isBlank() || item.id !in introProof)) { Text(stringResource(R.string.collections_remove_source)) }
                }
                HorizontalDivider()
            }
            TextButton(onClick = { picking = !picking }, enabled = !loading && sources.size < 10) { Text(stringResource(R.string.collections_add_notes)) }
            if (picking && sources.size < 10) {
                NotePickerPanel(Modifier.height(355.dp), sources.map { it.id }.toSet(), true, !loading, requestId,
                    search = { query, cursor -> searchPickerNotes(api, query, cursor) },
                    reportError = { res, failure ->
                        if (!ready()) throw CancellationException()
                        if (failure is ApiException && failure.status == 401) model.reauthenticate.value = true
                        if (failure is ApiException) pluginApiError(res, failure) else res.getString(R.string.sys_server_unreachable)
                    },
                    maxSelection = 10 - sources.size,
                    multiActions = { notes, clear ->
                        Button(onClick = {
                            notes.forEach { choose(CollectionSource(it.id, it.title, it.serverRev.toLong(), pinned = false, accepted = true)) }
                            clear()
                        }, enabled = notes.isNotEmpty() && !loading) { Text(stringResource(R.string.collections_add_note)) }
                    },
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(SpuntoSpacing.s8)) {
                Button(onClick = ::save, enabled = title.isNotBlank() && dirty && !loading) { Text(stringResource(R.string.collections_save)) }
                saved?.let { detail ->
                    if (!detail.bool("draft") && seedId == null)
                        TextButton(onClick = { loadDetail(detail); editing = false; picking = false }, enabled = !loading) {
                            Text(stringResource(R.string.core_close))
                        }
                    if (!detail.bool("draft")) {
                        TextButton(onClick = { showBrief = !showBrief }, enabled = !dirty) { Text(stringResource(R.string.collections_use_brief)) }
                        if (ask && detail.bool("ask_ready") && !dirty) TextButton(onClick = { nav.open("ask:${detail.string("id")}") }) { Text(stringResource(R.string.collections_ask)) }
                    }
                    TextButton(onClick = { confirmDelete = true }, enabled = !loading) { Text(stringResource(R.string.collections_delete)) }
                }
            }
            saved?.takeIf { ask && !dirty && !it.bool("draft") }?.let {
                Text(stringResource(if (it.bool("ask_ready")) R.string.collections_ask_guidance else R.string.collections_ask_ineligible))
            }
            briefPanel()

        }
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text(stringResource(R.string.collections_delete)) },
        text = { Text(stringResource(R.string.collections_confirm_delete)) },
        confirmButton = {
            TextButton(onClick = {
                confirmDelete = false
                val current = saved ?: return@TextButton
                launch {
                    post("/api/v1/collections", buildJsonObject { put("action", "delete"); put("id", current.string("id")); put("revision", current.pluginLong("revision")) })
                    if (ready()) nav.back()
                }
            }) { Text(stringResource(R.string.collections_delete)) }
        },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.core_close)) } },
    )
}
