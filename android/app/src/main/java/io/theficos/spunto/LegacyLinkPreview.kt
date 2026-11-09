package io.theficos.spunto

import io.theficos.spunto.core.model.Note
import io.theficos.spunto.core.plugins.objects
import io.theficos.spunto.core.plugins.string
import io.theficos.spunto.data.sync.ApiClient
import io.theficos.spunto.data.sync.noteFromWire
import kotlinx.serialization.json.JsonObject

// Current servers emit one canonical internal link. The cached payload is never consulted.
private val proposalLink = Regex("""^\[(?:\\[^\r\n]|[^\\\[\]\r\n])+\]\(/n/([a-f0-9]{8}-[a-f0-9]{4}-7[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12})\)$""")

internal fun legacyLinkDestination(pending: JsonObject, noteId: String, suggestionId: String): String? {
    val proposal = pending.objects("suggestions").firstOrNull {
        it.string("id") == suggestionId && it.string("note_id") == noteId &&
            it.string("plugin") == "links" && it.string("action") == "append_text" &&
            it.string("status") == "pending"
    } ?: return null
    val payload = proposal["payload"] as? JsonObject ?: return null
    return proposalLink.matchEntire(payload.string("text"))?.groupValues?.get(1)
}

internal suspend fun loadLegacyLinkPreview(api: ApiClient, noteId: String, suggestionId: String): Note? {
    val pending = api.request("/api/v1/suggestions", query = mapOf("scope" to "note", "note_id" to noteId)) as? JsonObject
        ?: return null
    val targetId = legacyLinkDestination(pending, noteId, suggestionId) ?: return null
    val target = noteFromWire(api.get("/api/v1/notes/$targetId"))
    return target.takeIf { it.id == targetId && !it.trashed }
}
