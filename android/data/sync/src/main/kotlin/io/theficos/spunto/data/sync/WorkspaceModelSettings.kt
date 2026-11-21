package io.theficos.spunto.data.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class WorkspaceModelChoice(val profile: String, val model: String)

data class WorkspaceModelSettings internal constructor(
    val canManage: Boolean,
    val revision: String?,
    val selections: Map<String, WorkspaceModelChoice>,
    val defaults: Map<String, WorkspaceModelChoice>,
    private val profiles: List<JsonObject>,
) {
    internal fun configuredModels(): Map<String, Set<String>> = profiles.associate { profile ->
        val capabilities = listOf("chat", "embed", "transcribe", "vision").filter {
            profile[it + "_model"]?.jsonPrimitive?.contentOrNull != null
        }
        profile.getValue("name").jsonPrimitive.content to capabilities.toSet()
    }

    fun leavesNetwork(capability: String, choice: WorkspaceModelChoice): Boolean? {
        val profile = profiles.firstOrNull {
            it["name"]?.jsonPrimitive?.content == choice.profile &&
                it[capability + "_model"]?.jsonPrimitive?.content == choice.model
        }
        return (profile?.get("leaves_network") as? JsonObject)?.get(capability)?.jsonPrimitive?.booleanOrNull
    }
}

data class WorkspaceModelOption(
    val choice: WorkspaceModelChoice,
    val leavesNetwork: Boolean,
    val capabilities: Set<String>?,
    val visionUnverified: Boolean,
    val upstreamModel: String?,
    val routeGroup: Int? = null,
) {
    fun unverified(capability: String): Boolean =
        capabilities == null || (capability == "vision" && visionUnverified)

    fun supports(capability: String): Boolean =
        unverified(capability) || capabilities?.contains(capability) == true
}

data class WorkspaceModelInventory(
    val options: List<WorkspaceModelOption>,
    val failedProfiles: List<String>,
    private val configuredModels: Map<String, Set<String>> = emptyMap(),
) {
    fun optionsFor(capability: String, effective: WorkspaceModelChoice?, inherited: WorkspaceModelChoice?): List<WorkspaceModelOption> {
        val supported = options.filter { it.supports(capability) }
        val verifiedVision = if (capability == "vision") supported.filter { !it.unverified(capability) }
            .mapNotNull { it.routeGroup }.toSet() else emptySet()
        val preferred = supported.sortedBy { option ->
            when {
                option.choice == effective -> 0
                option.choice.profile == effective?.profile -> 1
                option.choice.profile == inherited?.profile -> 2
                configuredModels[option.choice.profile]?.contains(capability) == true -> 3
                else -> 4
            }
        }
        val seen = mutableSetOf<Int>()
        return preferred.filter { option ->
            val group = option.routeGroup
            group == null || ((group !in verifiedVision || !option.unverified(capability) || option.choice == effective) && seen.add(group))
        }
    }
}

private val modelJson = Json { ignoreUnknownKeys = true }
private const val modelsPath = "/api/v1/ai/models"

@Serializable
private data class ModelStatus(
    @SerialName("can_manage") val canManage: Boolean = false,
    @SerialName("model_selection") val selection: Selection? = null,
    val profiles: List<JsonObject> = emptyList(),
) {
    @Serializable
    data class Selection(
        val revision: String,
        val selections: Map<String, WorkspaceModelChoice>,
        val defaults: Map<String, WorkspaceModelChoice>,
    )

    fun settings() = WorkspaceModelSettings(
        canManage, selection?.revision, selection?.selections.orEmpty(), selection?.defaults.orEmpty(), profiles,
    )
}

@Serializable
private data class InventoryResponse(val profiles: List<Profile>) {
    @Serializable
    data class Profile(val profile: String, val models: List<Model>, val error: String? = null)

    @Serializable
    data class Model(
        val id: String,
        val capabilities: Set<String>?,
        @SerialName("leaves_network") val leavesNetwork: Boolean,
        @SerialName("vision_unverified") val visionUnverified: Boolean = false,
        @SerialName("upstream_model") val upstreamModel: String? = null,
        @SerialName("route_group") val routeGroup: Int? = null,
    )
}

@Serializable
private data class SelectionRequest(val revision: String, val selections: Map<String, WorkspaceModelChoice>)

suspend fun ApiClient.workspaceModelSettings(): WorkspaceModelSettings =
    modelJson.decodeFromJsonElement(ModelStatus.serializer(), ai()).settings()

suspend fun ApiClient.workspaceModelInventory(settings: WorkspaceModelSettings): WorkspaceModelInventory {
    check(settings.canManage && !settings.revision.isNullOrBlank())
    val inventory = modelJson.decodeFromJsonElement(InventoryResponse.serializer(), get(modelsPath))
    val options = inventory.profiles.filter { it.error == null }.flatMap { profile ->
        profile.models.map { model ->
            WorkspaceModelOption(
                WorkspaceModelChoice(profile.profile, model.id), model.leavesNetwork,
                model.capabilities, model.visionUnverified, model.upstreamModel, model.routeGroup,
            )
        }
    }
    return WorkspaceModelInventory(
        options, inventory.profiles.filter { it.error != null }.map { it.profile }, settings.configuredModels(),
    )
}

suspend fun ApiClient.saveWorkspaceModelSettings(
    settings: WorkspaceModelSettings,
    selections: Map<String, WorkspaceModelChoice>,
): WorkspaceModelSettings {
    check(settings.canManage && !settings.revision.isNullOrBlank())
    val body = modelJson.encodeToJsonElement(SelectionRequest.serializer(), SelectionRequest(settings.revision, selections))
    return modelJson.decodeFromJsonElement(ModelStatus.serializer(), request(modelsPath, "PUT", body)).settings()
}
