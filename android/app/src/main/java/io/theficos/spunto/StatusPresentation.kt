package io.theficos.spunto

import io.theficos.spunto.data.sync.SyncStatus
import kotlinx.serialization.json.*
import java.net.URI

/** The Sync row's summary: what is waiting and when the phone last reached the server. */
internal fun syncSummary(status:SyncStatus,pending:Int,online:Boolean,lastSynced:Long?):String=when {
 status==SyncStatus.SignedOut -> "Connect a server to sync. You can keep writing offline."
 status==SyncStatus.NeedsSignIn -> "Paused until this phone signs in again. Your notes and edits are kept."
 status==SyncStatus.Running -> "Syncing…"
 !online -> "Offline. Notes are saved here and sync when you're connected."
 status is SyncStatus.Waiting && status.reason in setOf("different_identity","different_server") -> "This library belongs to a different account or server. Reconnect the original account."
 status is SyncStatus.Waiting -> "The last sync didn't finish. Your edits are safe here."
 else -> listOfNotNull(if(pending>0) "${if(pending==1) "1 change" else "$pending changes"} waiting" else "Up to date",lastSynced?.let {"last synced ${noteTime(it)}"}).joinToString(" · ")
}
/** "qwen3.5 · ollama.home.arpa · on your network", plus what to fix when its last check failed. */
internal fun aiCardSummary(card:AiCapabilityCard)=listOf(card.model,card.destination).filter {it.isNotBlank()}.joinToString(" · ")+if(card.healthy==false) ". Needs attention: ${card.detail}" else ""
internal data class AiCapabilityCard(val capability:String,val title:String,val model:String,val destination:String,val state:String,val detail:String,val healthy:Boolean?)
internal fun aiCapabilityCards(ai:JsonObject):List<AiCapabilityCard> {
 val profiles=(ai["profiles"] as? JsonArray).orEmpty().mapNotNull {it as? JsonObject}
 val bindings=ai["bindings"] as? JsonObject ?: JsonObject(emptyMap())
 val health=(ai["health"] as? JsonArray).orEmpty().mapNotNull {it as? JsonObject}
 return listOf("chat" to "Writing and chat","embed" to "Search and connections","vision" to "Image text","transcribe" to "Transcription").mapNotNull {(cap,title) ->
  val profile=profiles.firstOrNull {it.pluginText("name")==bindings.pluginText(cap)}
  val model=profile?.pluginText("${cap}_model").orEmpty()
  if(model.isEmpty() && cap !in setOf("chat","embed")) return@mapNotNull null
  val check=health.lastOrNull {it.pluginText("capability")==cap && it.pluginText("profile")==profile?.pluginText("name") && it.pluginText("model")==model}
  val ok=(check?.get("ok") as? JsonPrimitive)?.booleanOrNull
  val outside=((profile?.get("leaves_network") as? JsonObject)?.get(cap) as? JsonPrimitive)?.booleanOrNull
  val host=profile?.pluginText("base_url")?.let {runCatching {URI(it).host}.getOrNull()}
  AiCapabilityCard(cap,title,model.ifBlank {"No model selected"},host?.let {"$it · ${if(outside==true) "outside your network" else if(outside==false) "on your network" else "provider"}"}.orEmpty(),when {model.isBlank()->"Not configured";ok==true->"Check passed";ok==false->"Needs attention";else->"Not checked yet"},when {model.isBlank()->"Choose a model in AI settings on your server.";ok==false->check?.pluginText("fix_hint") ?: "Check the provider connection and model in AI settings.";ok==true->"Your server can reach this model.";else->"Run a check from the web app."},ok)
 }
}
internal fun aiIndexDescription(ai:JsonObject):String {
 val index=ai["indexing"] as? JsonObject ?: return "Search index information is not available yet."
 if((index["enabled"] as? JsonPrimitive)?.booleanOrNull!=true) return "AI search is switched off. Text search is always available."
 if((index["available"] as? JsonPrimitive)?.booleanOrNull!=true) return "The AI search index is unavailable. Check your server's vector support."
 val routes=(index["routes"] as? JsonArray).orEmpty().mapNotNull {it as? JsonObject}
 val total=routes.maxOfOrNull {(it["total"] as? JsonPrimitive)?.longOrNull ?: 0} ?: 0
 val indexed=routes.minOfOrNull {(it["indexed"] as? JsonPrimitive)?.longOrNull ?: 0} ?: 0
 return if(total==0L) "The index is ready for eligible notes." else "$indexed of $total eligible notes indexed. Private and machine-written notes are excluded."
}
