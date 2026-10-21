package io.theficos.spunto.data.sync

import io.theficos.spunto.core.model.*
import kotlinx.serialization.*
import kotlinx.serialization.json.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.resume
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.File
import java.time.Instant
import java.util.concurrent.TimeUnit

@Serializable data class DeviceToken(val id:String,val token:String,val name:String,@SerialName("user_id") val userId:String?=null,@SerialName("workspace_id") val workspaceId:String?=null,@SerialName("workspace_role") val workspaceRole:String?=null)
@Serializable data class PullItem(val kind:String,@SerialName("change_seq") val changeSeq:Long,val data:JsonObject)
@Serializable data class PullResponse(val epoch:String,val cursor:String,@SerialName("has_more") val hasMore:Boolean,@SerialName("plugins_rev") val pluginsRev:Int,val items:List<PullItem>)
@Serializable data class PushResult(@SerialName("op_id") val opId:String="",val status:String,val version:Int?=null,val note:JsonObject?=null,val body:String?=null,@SerialName("conflict_id") val conflictId:String?=null,val code:String?=null,val original:PushResult?=null)
@Serializable data class PushResponse(val epoch:String,val results:List<PushResult>)
@Serializable data class ReconcileResponse(val epoch:String,@SerialName("restore_ids") val restoreIds:List<String>)
/** [detail] is the problem document's safe sentence; [retryAfter] the seconds a rate limit asks to wait. */
class ApiException(val status:Int,val code:String,val detail:String="",val retryAfter:Long?=null):Exception("HTTP $status: $code")
private val defaultApiHttpClient=OkHttpClient.Builder().callTimeout(40,TimeUnit.SECONDS).readTimeout(35,TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
// Generation can outlive an ordinary sync request, including operator-installed tools.
private val pluginCommandPath=Regex("/api/v1/plugins/[a-z][a-z0-9-]{1,23}/commands/[a-z][a-z0-9-]{0,127}")
class ApiClient(val config:AuthConfig, client:OkHttpClient=defaultApiHttpClient,private val authority:suspend ()->Boolean={true}) {
 fun guarded(check:suspend ()->Boolean)=ApiClient(config,client,check)
 private suspend fun checkAuthority() {check(authority()) {"credentials_changed"}}
 private val client=client.newBuilder().followRedirects(false).followSslRedirects(false).build()
 private val extendedCommandClient by lazy { this.client.newBuilder().readTimeout(240,TimeUnit.SECONDS).callTimeout(240,TimeUnit.SECONDS).build() }
 private fun requestClient(path:String,method:String):OkHttpClient = if(method=="POST" && pluginCommandPath.matches(path) || method=="POST" && path=="/api/v1/ai/check") extendedCommandClient else client
 private val json=Json { ignoreUnknownKeys=true }
 init { val url=config.serverUrl.toHttpUrl(); require(url.scheme=="https" || config.httpConsent) { "Confirm the unencrypted connection before connecting." }; require(url.encodedPath=="/" && url.username.isEmpty() && url.password.isEmpty()) { "Use the server's root URL." } }
 suspend fun request(path:String, method:String="GET", body:JsonElement?=null, query:Map<String,String> = emptyMap()):JsonElement = withContext(Dispatchers.IO) {
  checkAuthority()
  val url=(config.serverUrl.trimEnd('/')+path).toHttpUrl().newBuilder().apply { query.forEach { (k,v)->addQueryParameter(k,v) } }.build()
  val builder=Request.Builder().url(url)
  if(config.token.isNotEmpty()) builder.header("Authorization","Bearer ${config.token}")
  if(method!="GET") builder.method(method,(body?:JsonObject(emptyMap())).toString().toRequestBody("application/json".toMediaType()))
  val result=requestClient(path,method).newCall(builder.build()).awaitDecodedResponse { response ->
   val text=response.body?.string().orEmpty()
   if(!response.isSuccessful) { val problem=runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull(); val field={ name:String -> runCatching { problem?.get(name)?.jsonPrimitive?.content }.getOrNull() }; throw ApiException(response.code,field("code")?:"request_failed",field("detail").orEmpty(),response.header("Retry-After")?.toLongOrNull()) }
   if(text.isBlank()) JsonObject(emptyMap()) else json.parseToJsonElement(text)
  }
  checkAuthority();result
 }
 suspend fun signIn(password:String,name:String):DeviceToken = json.decodeFromJsonElement(request("/api/v1/auth/devices","POST",buildJsonObject { put("password",password);put("name",name) }))
 suspend fun pairingExchange(code:String,name:String):DeviceToken = json.decodeFromJsonElement(request("/api/v1/auth/pairing/exchange","POST",buildJsonObject { put("code",code);put("name",name) }))
 /** Revokes this phone's own device credential; false when the server can't be told, so it stays listed under Devices. */
 suspend fun signOut():Boolean = try { request("/api/v1/auth/logout","POST");true } catch(e:CancellationException) { throw e } catch(e:ApiException) { e.status==401 } catch(e:Exception) { false }
 suspend fun get(path:String):JsonObject = request(path).jsonObject
 suspend fun post(path:String,body:JsonObject):JsonObject = request(path,"POST",body).jsonObject
 suspend fun ai():JsonObject = get("/api/v1/ai")
 suspend fun meta():JsonObject = request("/api/v1/meta").jsonObject
 suspend fun manifest():JsonObject = request("/api/v1/plugins/manifest").jsonObject
 suspend fun push(epoch:String,ops:List<OutboxOperation>):PushResponse = json.decodeFromJsonElement(request("/api/v1/sync/push","POST",buildJsonObject { put("epoch",epoch);put("ops",JsonArray(ops.map(::wireOperation))) }))
 suspend fun pull(cursor:String):PullResponse = json.decodeFromJsonElement(request("/api/v1/sync/pull",query=mapOf("cursor" to cursor,"limit" to "500")))
 suspend fun reconcile(epoch:String,notes:List<Note>):ReconcileResponse = json.decodeFromJsonElement(request("/api/v1/sync/reconcile","POST",buildJsonObject { put("epoch",epoch);put("notes",buildJsonArray { notes.forEach { note -> add(buildJsonObject { put("id",note.id);put("body_hash",note.bodyHash);put("updated_at",Instant.ofEpochMilli(note.updatedAt).toString()) }) } }) }))
 suspend fun waitForChanges(cursor:String,pluginsRev:Int=0):JsonObject = request("/api/v1/changes/wait",query=mapOf("cursor" to cursor,"plugins_rev" to pluginsRev.toString())).jsonObject
 suspend fun downloadAttachment(id:String,target:File,maximumBytes:Long):File=withContext(Dispatchers.IO) {
  checkAuthority()
  require(java.util.UUID.fromString(id).toString()==id) {"Invalid attachment ID."}
  require(maximumBytes in 1..1_000_000_000L)
  require(!target.exists()) {"Use a new private cache file."}
  target.parentFile?.mkdirs()
  val temporary=File.createTempFile("spunto-download-",".partial",target.parentFile)
  val context=currentCoroutineContext()
  try {
   val request=Request.Builder().url(config.serverUrl.trimEnd('/')+"/a/$id").header("Authorization","Bearer ${config.token}").build()
   client.newCall(request).awaitDecodedResponse {response ->
    if(!response.isSuccessful) throw ApiException(response.code,"attachment_failed")
    val body=response.body?:throw ApiException(502,"attachment_empty")
    if(body.contentLength()>maximumBytes) throw ApiException(413,"attachment_too_large")
    var size=0L
    body.byteStream().use {input ->temporary.outputStream().use {output ->val buffer=ByteArray(32*1024);while(true) {context.ensureActive();val count=input.read(buffer);if(count<0) break;size+=count;if(size>maximumBytes) throw ApiException(413,"attachment_too_large");output.write(buffer,0,count)}}}
    if(size==0L) throw ApiException(502,"attachment_empty")
    context.ensureActive()
    java.io.FileOutputStream(temporary,true).use {it.fd.sync()}
    java.nio.file.Files.move(temporary.toPath(),target.toPath(),java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    target
   }
  } finally {temporary.delete()}
 }
 suspend fun upload(path:String,mime:String):JsonObject = uploadNamed(path,mime,File(path).name)
 suspend fun uploadNamed(path:String,mime:String,filename:String):JsonObject = withContext(Dispatchers.IO) {
  checkAuthority()
  val file=File(path); val body=MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("file",filename,file.asRequestBody(mime.toMediaType())).build()
  val request=Request.Builder().url(config.serverUrl.trimEnd('/')+"/api/v1/attachments").header("Authorization","Bearer ${config.token}").post(body).build()
  val result=client.newCall(request).awaitDecodedResponse { response -> val content=response.body?.string().orEmpty(); if(!response.isSuccessful) throw ApiException(response.code,"attachment_failed"); json.parseToJsonElement(content).jsonObject }
  checkAuthority();result
 }
}
fun wireOperation(op:OutboxOperation):JsonObject = buildJsonObject {
 if(op.type=="note.restore") { op.machine?.let { put("machine",it) };op.origin?.let { put("origin",it) } }
 put("op_id",op.opId); put("type",op.type); op.noteId?.let { put("note_id",it) };op.body?.let { put("body",it) };op.baseVersion?.let { put("base_version",it) };op.createdAt?.let { put("created_at",it) };op.at?.let { put("at",it) }
 op.pinnedAt?.let { put("pinned_at",it) };op.archivedAt?.let { put("archived_at",it) };op.trashedAt?.let { put("trashed_at",it) };op.privateAt?.let { put("private_at",it) };
 op.pinned?.let { put("pinned",it) };op.archived?.let { put("archived",it) };op.trashed?.let { put("trashed",it) };op.privateNote?.let { put("private",it) };op.suggestionId?.let { put("suggestion_id",it) };op.decision?.let { put("decision",it) }
 op.sourceKind?.let { kind -> put("source",buildJsonObject { put("kind",kind);op.sourceRef?.let { put("ref",it) };op.sourceUrl?.let { put("url",it) } }) }
}
fun noteFromWire(data:JsonObject):Note {
 fun string(key:String)=data[key]?.jsonPrimitive?.contentOrNull
 fun time(key:String)=string(key)?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrElse { _ -> it.toLongOrNull()?:0 } }?:0
 fun bool(key:String)=data[key]?.jsonPrimitive?.booleanOrNull?:false
 val source=data["source"] as? JsonObject
 val flags=data["flag_times"] as? JsonObject
 fun flagTime(key:String)=flags?.get(key)?.jsonPrimitive?.longOrNull?:0
 return Note(id=string("id")!!,body=string("body").orEmpty(),title=string("title")?:titleOf(string("body").orEmpty()),createdAt=time("created_at"),updatedAt=time("updated_at"),serverRev=data["version"]?.jsonPrimitive?.intOrNull?:0,pinned=bool("pinned"),archived=bool("archived"),trashed=bool("trashed"),privateNote=bool("private"),machine=bool("machine"),conflictOf=string("conflict_of"),sourceUrl=source?.get("url")?.jsonPrimitive?.contentOrNull?:string("source_url"),sourceKind=source?.get("kind")?.jsonPrimitive?.contentOrNull?:string("source_kind")?:"android",sourceRef=source?.get("ref")?.jsonPrimitive?.contentOrNull?:string("source_ref"),pinnedAt=flagTime("pinned"),archivedAt=flagTime("archived"),trashedAt=flagTime("trashed"),privateAt=flagTime("private"),origin=string("origin")?:"owner",updatedViaAgent=bool("updated_via_agent"))
}

private suspend fun <T> Call.awaitDecodedResponse(decode:(Response)->T):T = suspendCancellableCoroutine { continuation ->
 continuation.invokeOnCancellation { cancel() }
 enqueue(object:Callback {
  override fun onFailure(call:Call,error:IOException) { if(continuation.isActive) continuation.resumeWithException(error) }
  override fun onResponse(call:Call,response:Response) {
   if(!continuation.isActive) { response.close();return }
   try {
    // Keep cancellation attached to the actual call through the complete body read.
    val decoded=response.use(decode)
    if(continuation.isActive) continuation.resume(decoded)
   } catch(error:Exception) { if(continuation.isActive) continuation.resumeWithException(error) }
  }
 })
}
