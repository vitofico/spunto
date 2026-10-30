package io.theficos.spunto

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import io.theficos.spunto.core.plugins.string
import io.theficos.spunto.core.plugins.objects

internal suspend fun completeAskRequest(request:suspend ()->JsonElement?,onComplete:()->Unit,onResult:(JsonElement)->Unit) {
 try {request()?.let(onResult)} finally {onComplete()}
}

/** Older servers use the same plugin version; the advertised command is the capability. */
internal fun supportsAskConversation(manifest:JsonObject):Boolean = manifest.objects("plugins").firstOrNull {it.string("id")=="ask"}?.let {plugin ->
 (plugin["contributes"] as? JsonObject)?.objects("commands")?.any {it.string("id")=="clear-conversation"}
} == true

/** Allocate a handle before inference so even the first pending turn can be cleared. */
internal suspend fun conversationAskRequest(question:String,continuation:JsonObject?,request:suspend (String,JsonObject)->JsonElement,current:()->Boolean,onHandle:(JsonObject)->Unit,conversationSupported:Boolean=true):JsonElement? {
 if(!conversationSupported) return if(current()) request("ask",buildJsonObject {put("question",question)}) else null
 val handle=continuation ?: withContext(NonCancellable) {
  val started=request("ask",buildJsonObject {put("start",true)})
  val allocated=askContinuation(started) ?: error("Could not start a conversation.")
  if(!current()) {request("clear-conversation",buildJsonObject {put("conversation_id",allocated.string("conversation_id"))});null}
  else {onHandle(allocated);allocated}
 } ?: return null
 currentCoroutineContext().ensureActive()
 if(!current()) return null
 return request("ask",buildJsonObject {handle.forEach {(key,value)->put(key,value)};put("question",question)})
}

internal fun canAskSearch(question:String):Boolean {
 val text=question.trim()
 return text.isNotEmpty() && text.split(Regex("\\s+")).size>=3 && text.codePointCount(0,text.length)<=500
}
