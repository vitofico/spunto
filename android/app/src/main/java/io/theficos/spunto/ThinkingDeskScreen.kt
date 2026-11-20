package io.theficos.spunto

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.theficos.spunto.core.design.SpuntoSpacing
import io.theficos.spunto.core.plugins.objects
import io.theficos.spunto.core.plugins.outputComponents
import io.theficos.spunto.core.plugins.string
import kotlinx.serialization.json.JsonObject

internal const val ThinkingDeskRoute = "thinking-desk"

internal fun thinkingDeskAvailable(manifest: JsonObject, owner: Boolean): Boolean {
    val plugin = manifest.objects("plugins").singleOrNull { it.string("id") == ThinkingDeskRoute }
    return owner && plugin != null && ("enabled" !in plugin || plugin.pluginFlag("enabled") == true) &&
        plugin.string("origin") != "operator" && plugin.string("source_kind") !in setOf("folder", "entrypoint") &&
        plugin.string("state") in setOf("", "enabled")
}

internal data class SavedThinkingDesk(val title: String, val args: JsonObject)

internal data class ThinkingDeskIndex(
    val newTitle: String,
    val newArgs: JsonObject,
    val desks: List<SavedThinkingDesk>,
    val more: Pair<String, JsonObject>?,
)

/** The bundled server sends each full title immediately before its open command. */
internal fun thinkingDeskIndex(result: JsonObject): ThinkingDeskIndex? {
    val components = outputComponents(result)
    fun deskArgs(component: JsonObject): JsonObject? =
        (component["args"] as? JsonObject)?.takeIf {
            component.string("type") == "command" && component.string("id") == "desk"
        }
    val start = components.firstOrNull() ?: return null
    val newArgs = deskArgs(start)?.takeIf { it.string("action") == "new" } ?: return null
    val desks = components.zipWithNext().mapNotNull { (title, open) ->
        val args = deskArgs(open)?.takeIf { it.string("desk_id").isNotBlank() }
        if (args == null || title.string("type") != "text" || title.string("text").isBlank()) null
        else SavedThinkingDesk(title.string("text"), args)
    }
    val more = components.lastOrNull()?.let { component ->
        deskArgs(component)?.takeIf { it.string("cursor").isNotBlank() }?.let { component.string("title") to it }
    }
    return ThinkingDeskIndex(start.string("title"), newArgs, desks, more)
}

/** Kept by the navigation entry while a related note is open. Bodies stay in memory only. */
internal class ThinkingDeskSession {
    var result by mutableStateOf<JsonObject?>(null)
        private set
    var index by mutableStateOf<ThinkingDeskIndex?>(null)
        private set
    var attempt by mutableIntStateOf(0)
        private set
    var busy by mutableStateOf(false)
    var error by mutableStateOf("")
    val insideDesk: Boolean
        get() = result?.let { response ->
            val args = response["command_args"] as? JsonObject ?: JsonObject(emptyMap())
            response.string("command_id") != "desk" ||
                args.string("desk_id").isNotBlank() || args.string("action").isNotBlank()
        } == true

    fun reset() {
        result = null
        index = null
        error = ""
        attempt++
    }

    fun receive(response: JsonObject) {
        val args = response["command_args"] as? JsonObject
        val received = thinkingDeskIndex(response)
        index = received?.let { page ->
            if (args?.string("cursor").isNullOrBlank()) page
            else page.copy(desks = (index?.desks.orEmpty() + page.desks).distinctBy { it.args.string("desk_id") })
        }
        result = response
    }
}

@Composable internal fun ThinkingDeskScreen(model: SpuntoViewModel, session: ThinkingDeskSession, onNote: (String) -> Unit) {
    val resources = LocalContext.current.resources
    val scope = rememberCoroutineScope()
    val online by model.online.collectAsState()
    fun runDesk(args: JsonObject) {
        if (!online || session.busy) return
        session.busy = true
        session.error = ""
        runCommand(model, ThinkingDeskRoute, "desk", args, session::receive,
            onComplete = { session.busy = false }, scope = scope,
            onError = { session.error = it }, requireOwner = true)
    }
    LaunchedEffect(session, session.attempt, online) {
        if (online && session.result == null) runDesk(JsonObject(emptyMap()))
    }
    val index = session.index
    key(if (index != null) "index" else session.result) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = SpuntoSpacing.s20, vertical = SpuntoSpacing.s16),
            verticalArrangement = Arrangement.spacedBy(SpuntoSpacing.s12),
        ) {
            if (!online) {
                Text(resources.getString(R.string.settings_connect_to_the_server_to_use_this))
            }
            if (index != null) {
                Text(resources.getString(R.string.settings_thinking_desk_resume),
                    modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
                Button(onClick = { runDesk(index.newArgs) }, enabled = online && !session.busy) { Text(index.newTitle) }
                for (desk in index.desks) {
                    OutlinedCard(onClick = { runDesk(desk.args) }, enabled = online && !session.busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(desk.title, Modifier.padding(SpuntoSpacing.s16), style = MaterialTheme.typography.titleMedium)
                    }
                }
                index.more?.let { (title, args) ->
                    TextButton(onClick = { runDesk(args) }, enabled = online && !session.busy) { Text(title) }
                }
            } else {
                session.result?.let { result ->
                    RenderOutput(model, result, onNote, session::receive, onRunning = { session.busy = it })
                }
            }
            if (session.busy && (index != null || session.result == null)) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (session.error.isNotBlank()) {
                Text(session.error, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                if (session.result == null) TextButton(onClick = session::reset, enabled = online) {
                    Text(resources.getString(R.string.core_ask_retry))
                }
            }
        }
    }
}
