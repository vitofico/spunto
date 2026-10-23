package io.theficos.spunto

import android.content.res.Resources

import io.theficos.spunto.data.sync.SyncStatus
import kotlinx.serialization.json.*
import java.net.URI

/** The Sync row's summary: what is waiting and when the phone last reached the server. */
internal fun syncSummary(resources:Resources,status:SyncStatus,pending:Int,online:Boolean,lastSynced:Long?):String=when {
 status==SyncStatus.SignedOut -> resources.getString(R.string.settings_connect_a_server_to_sync_you_can)
 status==SyncStatus.NeedsSignIn -> resources.getString(R.string.settings_paused_until_this_phone_signs_in_again)
 status==SyncStatus.Running -> resources.getString(R.string.settings_syncing)
 !online -> resources.getString(R.string.settings_offline_notes_are_saved_here_and_sync)
 status is SyncStatus.Waiting && status.reason in setOf("different_identity","different_server") -> resources.getString(R.string.settings_this_library_belongs_to_a_different_account)
 status is SyncStatus.Waiting -> resources.getString(R.string.settings_the_last_sync_didn_t_finish_your)
 else -> listOfNotNull(if(pending>0) resources.getQuantityString(R.plurals.settings_changes_waiting,pending,pending) else resources.getString(R.string.settings_up_to_date),lastSynced?.let {resources.getString(R.string.settings_last_synced ,noteTime(it,resources.configuration.locales[0]))}).joinToString(" · ")
}
/** "qwen3.5 · ollama.home.arpa · on your network", plus what to fix when its last check failed. */
internal fun aiCardSummary(resources:Resources,card:AiCapabilityCard)=listOf(card.model,card.destination).filter {it.isNotBlank()}.joinToString(" · ")+if(card.healthy==false) resources.getString(R.string.settings_needs_attention ,card.detail) else ""
internal data class AiCapabilityCard(val capability:String,val title:String,val model:String,val destination:String,val state:String,val detail:String,val healthy:Boolean?)
internal fun aiCapabilityCards(resources:Resources,ai:JsonObject):List<AiCapabilityCard> {
 val profiles=(ai["profiles"] as? JsonArray).orEmpty().mapNotNull {it as? JsonObject}
 val bindings=ai["bindings"] as? JsonObject ?: JsonObject(emptyMap())
 val health=(ai["health"] as? JsonArray).orEmpty().mapNotNull {it as? JsonObject}
 return listOf("chat" to resources.getString(R.string.settings_writing_and_chat),"embed" to resources.getString(R.string.settings_search_and_connections),"vision" to resources.getString(R.string.settings_image_text),"transcribe" to resources.getString(R.string.settings_transcription)).mapNotNull {(cap,title) ->
  val profile=profiles.firstOrNull {it.pluginText("name")==bindings.pluginText(cap)}
  val model=profile?.pluginText("${cap}_model").orEmpty()
  if(model.isEmpty() && cap !in setOf("chat","embed")) return@mapNotNull null
  val check=health.lastOrNull {it.pluginText("capability")==cap && it.pluginText("profile")==profile?.pluginText("name") && it.pluginText("model")==model}
  val ok=(check?.get("ok") as? JsonPrimitive)?.booleanOrNull
  val outside=((profile?.get("leaves_network") as? JsonObject)?.get(cap) as? JsonPrimitive)?.booleanOrNull
  val host=profile?.pluginText("base_url")?.let {runCatching {URI(it).host}.getOrNull()}
  AiCapabilityCard(cap,title,model.ifBlank {resources.getString(R.string.settings_no_model_selected)},host?.let {"$it · ${if(outside==true) resources.getString(R.string.settings_outside_your_network) else if(outside==false) resources.getString(R.string.settings_on_your_network) else resources.getString(R.string.settings_provider)}"}.orEmpty(),when {model.isBlank()->resources.getString(R.string.settings_not_configured);ok==true->resources.getString(R.string.settings_check_passed);ok==false->resources.getString(R.string.settings_needs_attention_a1267);else->resources.getString(R.string.settings_not_checked_yet)},when {model.isBlank()->resources.getString(R.string.settings_choose_a_model_in_ai_settings_on);ok==false->check?.pluginText("fix_hint") ?: resources.getString(R.string.settings_check_the_provider_connection_and_model_in);ok==true->resources.getString(R.string.settings_your_server_can_reach_this_model);else->resources.getString(R.string.settings_run_a_check_from_the_web_app)},ok)
 }
}
internal fun aiIndexDescription(resources:Resources,ai:JsonObject):String {
 val index=ai["indexing"] as? JsonObject ?: return resources.getString(R.string.settings_search_index_information_is_not_available_yet)
 if((index["enabled"] as? JsonPrimitive)?.booleanOrNull!=true) return resources.getString(R.string.settings_ai_search_is_switched_off_text_search)
 if((index["available"] as? JsonPrimitive)?.booleanOrNull!=true) return resources.getString(R.string.settings_the_ai_search_index_is_unavailable_check)
 val routes=(index["routes"] as? JsonArray).orEmpty().mapNotNull {it as? JsonObject}
 val total=routes.maxOfOrNull {(it["total"] as? JsonPrimitive)?.longOrNull ?: 0} ?: 0
 val indexed=routes.minOfOrNull {(it["indexed"] as? JsonPrimitive)?.longOrNull ?: 0} ?: 0
 return if(total==0L) resources.getString(R.string.settings_the_index_is_ready_for_eligible_notes) else resources.getString(R.string.settings_of_eligible_notes_indexed_private_and_machine ,indexed,total)
}
