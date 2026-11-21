package io.theficos.spunto.data.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
data class SharedEvent(
    val id: String,
    val kind: String,
    @SerialName("workspace_id") val workspaceId: String,
    @SerialName("workspace_name") val workspaceName: String,
    @SerialName("note_id") val noteId: String? = null,
    @SerialName("created_at") val createdAt: Long,
    val read: Boolean,
)

@Serializable
data class SharedFeed(val notifications: List<SharedEvent>, val following: Boolean, val followable: Boolean)

private val feedJson = Json { ignoreUnknownKeys = true }
suspend fun ApiClient.sharedActivity(): SharedFeed =
    feedJson.decodeFromJsonElement(SharedFeed.serializer(), request("/api/v1/notifications"))

suspend fun ApiClient.followSharedSpace(enabled: Boolean) {
    request("/api/v1/notifications/follow", "PUT", buildJsonObject { put("following", enabled) })
}

suspend fun ApiClient.readSharedEvent(event: SharedEvent) {
    request("/api/v1/notifications/${android.net.Uri.encode(event.id)}/read", "POST")
}
