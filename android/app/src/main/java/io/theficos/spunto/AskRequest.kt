package io.theficos.spunto

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import io.theficos.spunto.core.plugins.string
import io.theficos.spunto.core.plugins.objects
import io.theficos.spunto.core.plugins.bool

internal suspend fun completeAskRequest(request:suspend ()->JsonElement?,onComplete:()->Unit,onResult:(JsonElement)->Unit) {
 try {request()?.let(onResult)} finally {onComplete()}
}

/** Older servers use the same plugin version; the advertised command is the capability. */
internal fun supportsAskConversation(manifest:JsonObject):Boolean = manifest.objects("plugins").firstOrNull {it.string("id")=="ask"}?.let {plugin ->
 (plugin["contributes"] as? JsonObject)?.objects("commands")?.any {it.string("id")=="clear-conversation"}
} == true

internal fun supportsConversationalAsk(manifest:JsonObject):Boolean =
 supportsAskVersion(manifest,2)

/** Only a released manifest that advertises reviewed writes may receive the opt-in argument. */
internal fun supportsAskNoteActions(manifest:JsonObject,workspaceRole:String?=null):Boolean {
 return workspaceRole in setOf(null,"owner","editor") && supportsAskVersion(manifest,1)
}

private fun supportsAskVersion(manifest:JsonObject,minMinor:Int):Boolean {
 if(!supportsAskConversation(manifest)) return false
 val plugin=manifest.objects("plugins").firstOrNull {it.string("id")=="ask"} ?: return false
 if(!plugin.bool("enabled",true) || plugin.string("state") !in setOf("","enabled")) return false
 val version=Regex("""^(\d+)\.(\d+)\.(\d+)(?:\+[\w.-]+)?$""").matchEntire(plugin.string("version")) ?: return false
 val major=version.groupValues[1].toIntOrNull() ?: return false
 val minor=version.groupValues[2].toIntOrNull() ?: return false
 return major>1 || major==1 && minor>=minMinor
}

/** Allocate a handle before inference so even the first pending turn can be cleared. */
internal suspend fun conversationAskRequest(question:String,continuation:JsonObject?,request:suspend (String,JsonObject)->JsonElement,current:()->Boolean,onHandle:(JsonObject)->Unit,conversationSupported:Boolean=true,noteActionsSupported:Boolean=false,conversational:Boolean=false,notesOnly:Boolean=false,collectionId:String?=null,chatModel:String?=null,chatModelLocalOnly:Boolean=false):JsonElement? {
 if(!conversationSupported) return if(current()) request("ask",buildJsonObject {put("question",question)}) else null
 val handle=continuation ?: withContext(NonCancellable) {
  val started=request("ask",buildJsonObject {put("start",true);chatModel?.let {put("chat_model",it);if(chatModelLocalOnly) put("chat_model_local_only",true)};if(noteActionsSupported && collectionId==null) put("note_actions",true);if(conversational) put("conversational",true);collectionId?.let {put("collection_id",it)}})
  val allocated=askContinuation(started) ?: error("Could not start a conversation.")
  if(!current()) {request("clear-conversation",buildJsonObject {put("conversation_id",allocated.string("conversation_id"))});null}
  else {onHandle(allocated);allocated}
 } ?: return null
 currentCoroutineContext().ensureActive()
 if(!current()) return null
 return request("ask",buildJsonObject {handle.forEach {(key,value)->put(key,value)};put("question",question);chatModel?.let {put("chat_model",it);if(chatModelLocalOnly) put("chat_model_local_only",true)};if(conversational && notesOnly) put("notes_only",true)})
}
