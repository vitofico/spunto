package io.theficos.spunto

import androidx.compose.runtime.*
import io.theficos.spunto.core.model.Note
import io.theficos.spunto.data.local.AttachmentEntity
import io.theficos.spunto.data.sync.ApiException
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonObject

internal data class NoteToolInputs(
    val image: Boolean = false,
    val audio: Boolean = false,
    val link: Boolean = false,
    val titleSummary: Boolean = false,
    val labels: Boolean = false,
    val shortText: Boolean = false,
)

internal sealed interface AgentReviewDetail {
    data object Created : AgentReviewDetail
    data class Edited(val before: String?) : AgentReviewDetail
}

internal fun parseAgentReview(detail: JsonObject?): AgentReviewDetail? {
    val review = detail?.get("agent_review") as? JsonObject ?: return null
    return when (review.pluginText("kind")) {
        "created" -> AgentReviewDetail.Created
        "edited" -> {
            val before = review["before"] as? JsonObject
            val version = before?.pluginLong("version")
            val current = detail.pluginLong("version")
            AgentReviewDetail.Edited(before?.pluginText("body")?.takeIf {
                version != null && current != null && version in 1 until current
            })
        }
        else -> null
    }
}

internal fun parseNoteToolInputs(detail: JsonObject?): NoteToolInputs {
    val inputs = detail?.get("note_tool_inputs") as? JsonObject ?: return NoteToolInputs()
    return NoteToolInputs(
        image = inputs.pluginFlag("image") == true,
        audio = inputs.pluginFlag("audio") == true,
        link = inputs.pluginFlag("link") == true,
        titleSummary = inputs.pluginFlag("title_summary") == true,
        labels = inputs.pluginFlag("labels") == true,
        shortText = inputs.pluginFlag("short_text") == true,
    )
}

internal data class NoteToolScope(val server: String, val epoch: String)
private val attachmentReference = Regex("/a/([0-9a-fA-F-]{36})(?![0-9a-fA-F-])")
private val sourceLink = Regex("https?://[^\\s<>\\]\\)]+")
private val imageTypes = setOf("image/png", "image/jpeg", "image/webp", "image/gif")
private val audioTypes = setOf("audio/ogg", "audio/opus", "audio/wav", "audio/x-wav")

/** Older note detail has no inputs. Use only data the saved note and authenticated replica can prove. */
internal fun legacyNoteToolInputs(note: Note, scope: NoteToolScope, attachments: List<AttachmentEntity>): NoteToolInputs {
    if (note.privateNote || note.machine || note.trashed || note.pending || note.syncError != null || note.serverRev <= 0)
        return NoteToolInputs()
    var words = 0
    var inWord = false
    for (character in note.body) {
        val word = !character.isWhitespace() && character != '\u0085'
        if (word && !inWord && ++words == 300) break
        inWord = word
    }
    val candidate = note.sourceUrl?.takeIf(String::isNotEmpty) ?: sourceLink.find(note.body)?.value
    val url = candidate?.takeIf { it.length <= 2048 && it.none { c -> c <= ' ' || c == '\u007f' || c == '\\' } }
        ?.let { runCatching { URI(it) }.getOrNull() }
    val link = url != null && url.scheme?.lowercase() in setOf("http", "https") &&
        !url.host.isNullOrEmpty() && url.rawAuthority?.contains('@') == false
    val ids = attachmentReference.findAll(note.body).map { it.groupValues[1] }.toSet()
    val types = attachments.filter {
        it.noteId == note.id && it.status == "uploaded" && it.serverUrl == scope.server &&
            it.serverEpoch == scope.epoch && it.remoteId in ids
    }.map { it.mime }
    return NoteToolInputs(types.any(imageTypes::contains), types.any(audioTypes::contains), link,
        titleSummary = words >= 300, labels = words >= 3,
        shortText = words > 0 && note.body.codePointCount(0, note.body.length) <= 20_000)
}

internal fun matchesNoteToolDetail(note: Note, detail: JsonObject): Boolean =
    note.serverRev > 0 && !note.pending && !note.trashed && note.syncError == null &&
        detail.pluginText("id") == note.id && detail.pluginLong("version") == note.serverRev.toLong() &&
        detail.pluginText("body") == note.body && detail.pluginFlag("private") == note.privateNote &&
        detail.pluginFlag("machine") == note.machine

internal data class NoteToolDetail(val loading: Boolean = false, val data: JsonObject? = null, val legacyScope: NoteToolScope? = null)

/** Volatile detail for the exact saved revision. Keep it scoped to the current account and replica epoch. */
@Composable
internal fun rememberNoteToolDetail(model: SpuntoViewModel, note: Note): NoteToolDetail {
    val auth by model.auth.collectAsState()
    val online by model.online.collectAsState()
    val config = auth
    val dao = model.repository.dao
    val server = config?.serverUrl?.trimEnd('/')
    val epoch by remember(dao, server) {
        dao.observeState().map { state -> state?.epoch?.takeIf { state.serverUrl == server } }
    }.collectAsState(null)
    val canFetch = config != null && online && !note.trashed && !note.pending && note.syncError == null && note.serverRev > 0
    val shouldFetch = config != null && canFetch && !epoch.isNullOrEmpty()
    var state by remember(model, note, config, epoch, canFetch) { mutableStateOf(NoteToolDetail(canFetch)) }
    val expectedEpoch = epoch
    LaunchedEffect(model, note, config, expectedEpoch, shouldFetch) {
        if (!shouldFetch) return@LaunchedEffect
        suspend fun current(): Boolean = model.auth.value == config && model.online.value && dao.state()?.let {
            it.epoch == expectedEpoch && it.serverUrl == server
        } == true
        val detail = try {
            pluginApi(config, model.container.context.resources).guarded { current() }.get("/api/v1/notes/${note.id}")
        } catch (error: CancellationException) {
            throw error
        } catch (error: ApiException) {
            if (error.status == 401 && current()) model.reauthenticate.value = true
            null
        } catch (_: Exception) {
            null
        }
        if (current()) {
            val matching = detail?.takeIf { matchesNoteToolDetail(note, it) }
            state = NoteToolDetail(data = matching, legacyScope = if (matching != null && "note_tool_inputs" !in matching)
                NoteToolScope(requireNotNull(server), requireNotNull(expectedEpoch)) else null)
        }
    }
    return state
}
