package io.theficos.spunto.data.sync

import androidx.room.withTransaction
import io.theficos.spunto.core.model.*
import io.theficos.spunto.data.local.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

sealed interface SyncStatus {
 data object Idle:SyncStatus
 data object Running:SyncStatus
 data object SignedOut:SyncStatus
 data object NeedsSignIn:SyncStatus
 data class Waiting(val reason:String):SyncStatus
 data class Complete(val pending:Int):SyncStatus
}
class SyncEngine(private val repository:NotesRepository, private val configProvider:suspend ()->AuthConfig?, private val apiFactory:(AuthConfig)->ApiClient = { ApiClient(it) }) {
 constructor(repository:NotesRepository,auth:AuthStore,apiFactory:(AuthConfig)->ApiClient = { ApiClient(it) }):this(repository,{ auth.config.first() },apiFactory)
 private val mutex=globalMutex; private val mutableState=MutableStateFlow<SyncStatus>(SyncStatus.Idle)
 companion object { private val globalMutex=Mutex() }
 val state:StateFlow<SyncStatus> = mutableState.asStateFlow()
 private var syncingConfig:AuthConfig?=null
 private suspend fun owns(config:AuthConfig):Boolean=configProvider()==config && config.ownsReplica(repository.replicaId)
 private suspend fun <T> ownedTransaction(block:suspend ()->T):T=repository.db.withTransaction {
  val config=checkNotNull(syncingConfig);check(owns(config)) {"credentials_changed"};val result=block();check(owns(config)) {"credentials_changed"};result
 }
 suspend fun sync():SyncStatus = mutex.withLock {
  val config=configProvider() ?: return@withLock SyncStatus.SignedOut.also { mutableState.value=it }
  if(!owns(config)) return@withLock SyncStatus.Waiting("different_identity").also {mutableState.value=it}
  syncingConfig=config
  mutableState.value=SyncStatus.Running
  try {
   val api=apiFactory(config).guarded {owns(config)}; val dao=repository.dao
   var state=dao.state()?:SyncStateEntity()
   val serverUrl=config.serverUrl.trimEnd('/')
   if(state.serverUrl.isNotEmpty() && state.serverUrl!=serverUrl) return@withLock SyncStatus.Waiting("different_server").also { mutableState.value=it }
   if(state.replicaId.isNotEmpty() && state.replicaId!=config.replicaId) return@withLock SyncStatus.Waiting("different_identity").also {mutableState.value=it}
   state=state.copy(replicaId=config.replicaId);ownedTransaction {dao.putState(state)}
   val previousEpoch=state.epoch
   val meta=api.meta(); val epoch=meta["sync_epoch"]?.jsonPrimitive?.content ?: state.epoch
   val maximum=(meta["max_upload_bytes"] as? JsonPrimitive)?.takeUnless {it.isString}?.longOrNull?.takeIf {it in 1_000_000L..1_000_000_000L}?:LEGACY_UPLOAD_LIMIT
   state=state.copy(maxUploadBytes=maximum);putStateKeepingPluginCache(state)
   if(epoch.isEmpty()) throw IllegalStateException("missing_epoch")
   if(state.serverUrl.isEmpty()) { state=state.copy(serverUrl=serverUrl);putStateKeepingPluginCache(state) }
   if(state.epoch.isNotEmpty() && state.epoch!=epoch) reconcile(api,epoch)
   state=dao.state()?:state
   if(state.epoch!=epoch) { state=state.copy(epoch=epoch,cursor="$epoch.0",manifest="{}",catalog="{}"); ownedTransaction {dao.putState(state)} }
   if(previousEpoch.isNotEmpty() && previousEpoch!=epoch) ownedTransaction {
    for(item in dao.attachmentSnapshots()) {
     val note=dao.get(item.noteId)
     if(item.remoteId!=null && item.marker.isNotEmpty() && item.marker in note?.body.orEmpty() && note?.trashed==false && dao.tombstone(item.noteId)==null && item.serverUrl==serverUrl && java.io.File(item.localPath).isFile) dao.putAttachment(item.copy(remoteId=null,status="queued",error=null))
    }
   }
   if(dao.pendingAttachments().any {it.kind=="recording"}) {
    val manifest=api.manifest();val catalog=api.get("/api/v1/plugins")
    persistPluginSnapshot(repository,config,epoch,manifest,catalog,configProvider=configProvider)
   }
   uploadAttachments(api)
   var batches=0
   while(batches++<1000) {
    // Once sent, the idempotency key and payload are immutable across failures.
    val batch=ownedTransaction { dao.outbox().filter { it.error==null }.let { queued -> val restores=queued.filter { Json.decodeFromString<OutboxOperation>(it.payload).type=="note.restore" }; val decisions=queued.filter { Json.decodeFromString<OutboxOperation>(it.payload).type=="suggestion.decide" }; (if(restores.isNotEmpty() && decisions.isNotEmpty()) decisions.distinctBy { it.noteId?:it.opId } else if(restores.isNotEmpty()) restores else queued.distinctBy { it.noteId?:it.opId }).take(100) }.also { dao.markSent(it.map(OutboxEntity::opId)) } }
    if(batch.isEmpty()) break
    val response=api.push(epoch,batch.map { Json.decodeFromString<OutboxOperation>(it.payload) })
    if(response.epoch!=epoch) throw ApiException(409,"resync_required")
    for(result in response.results) acknowledge(result,batch)
    refreshOriginals(api)
    if(response.results.none { it.status in listOf("applied","merged","conflict","duplicate","rejected") }) break
   }
   refreshOriginals(api)
   var more=true
   while(more) {
    state=dao.state()?:state
    val page=api.pull(state.cursor.ifEmpty { "$epoch.0" })
    if(page.epoch!=epoch) throw ApiException(409,"resync_required")
    ownedTransaction {
     for(item in page.items) applyPull(item)
     putStateKeepingPluginCache(state.copy(epoch=page.epoch,cursor=page.cursor,pluginsRev=page.pluginsRev))
    }
    more=page.hasMore
   }
   state=dao.state()?:state
   // Manifest is stored with the replica so plugin switches and output render offline.
   val manifest=api.manifest()
   val catalog=api.get("/api/v1/plugins")
   persistPluginSnapshot(repository,config,epoch,manifest,catalog,configProvider=configProvider)
   val pending=dao.outbox().size
   repository.record("complete")
   SyncStatus.Complete(pending).also { mutableState.value=it }
  } catch(cancelled:CancellationException) { throw cancelled }
  catch(error:Exception) {
   val reason=if(error is ApiException) error.code else "connection_failed"
   repository.record("failed",reason)
   val result=if(error is ApiException && error.status==401) SyncStatus.NeedsSignIn else SyncStatus.Waiting(reason)
   mutableState.value=result; result
  }
 }
 private suspend fun putStateKeepingPluginCache(state:SyncStateEntity)=ownedTransaction {
  val current=repository.dao.state()
  val sameOwner=current!=null && current.serverUrl==state.serverUrl && current.epoch==state.epoch
  repository.dao.putState(if(sameOwner) state.copy(manifest=current!!.manifest,catalog=current.catalog,pluginsRev=maxOf(state.pluginsRev,current.pluginsRev)) else state)
 }
 private suspend fun reconcile(api:ApiClient,epoch:String) {
  val dao=repository.dao
  val notes=dao.all().map { it.note() }.filter { dao.tombstone(it.id)==null }
  for(chunk in notes.chunked(1000)) {
   val response=api.reconcile(epoch,chunk)
   ownedTransaction {
    for(id in response.restoreIds) {
     // Pending notes are represented by a restore of their latest local body;
     // retain existing operations unchanged, then replay against restored version.
     val note=dao.get(id)?.note()?:continue
     if(dao.tombstone(id)!=null) continue
     if(dao.outbox().any { Json.decodeFromString<OutboxOperation>(it.payload).let { op -> op.type=="note.restore" && op.noteId==id } }) continue
     val restore=OutboxOperation(type="note.restore",noteId=id,body=note.body,baseVersion=note.serverRev.takeIf { it>0 },createdAt=note.createdAt,at=note.updatedAt,pinned=note.pinned,archived=note.archived,trashed=note.trashed,privateNote=note.privateNote,sourceKind=note.sourceKind,sourceRef=note.sourceRef,sourceUrl=note.sourceUrl,machine=note.machine,origin=note.origin,pinnedAt=note.pinnedAt,archivedAt=note.archivedAt,trashedAt=note.trashedAt,privateAt=note.privateAt,supersededOpIds=dao.outbox().filter { queued -> queued.noteId==id && Json.decodeFromString<OutboxOperation>(queued.payload).type in listOf("note.create","note.update","note.flags") }.map { it.opId })
     dao.markSent(restore.supersededOpIds)
     dao.putOutbox(OutboxEntity(restore.opId,id,Json.encodeToString(restore),Long.MIN_VALUE+System.currentTimeMillis()))
     repository.putNote(note.copy(pending=true))
    }
   }
  }
  val old=dao.state()?:SyncStateEntity(); ownedTransaction {dao.putState(old.copy(epoch=epoch,cursor="$epoch.0",manifest="{}",catalog="{}"))}
 }
 private suspend fun acknowledge(result:PushResult,batch:List<OutboxEntity>) = ownedTransaction {
  val dao=repository.dao; val sent=batch.firstOrNull { it.opId==result.opId }?:return@ownedTransaction
  val effective=if(result.status=="duplicate") result.original?:result else result
  if(effective.status=="rejected") { dao.reject(sent.opId,effective.code?:"rejected"); sent.noteId?.let { dao.get(it)?.note()?.let { note -> repository.putNote(note.copy(syncError=effective.code?:"rejected",pending=true)) } }; return@ownedTransaction }
  if(effective.status !in listOf("applied","merged","conflict","duplicate")) return@ownedTransaction
  dao.removeOutbox(sent.opId)
  val sentOperation=Json.decodeFromString<OutboxOperation>(sent.payload)
  if(sentOperation.type=="note.restore") sentOperation.supersededOpIds.forEach { dao.removeOutbox(it) }
  val id=sent.noteId?:return@ownedTransaction
  val note=dao.get(id)?.note()?:return@ownedTransaction
  val remaining=dao.outbox().filter { it.noteId==id }
  val target=effective.conflictId?:id
  if(target!=id) dao.putRefresh(RefreshNoteEntity(id))
  val version=if(target!=id) effective.note?.get("version")?.jsonPrimitive?.intOrNull?:1 else effective.version?:note.serverRev
  val canRebase=target!=id || (effective.status=="applied" && sentOperation.type in listOf("note.create","note.update","note.restore"))
  // Only a confirmed unchanged body is a safe new ancestor for later typing.
  for(queued in remaining.filter { !it.sent }) {
   val op=Json.decodeFromString<OutboxOperation>(queued.payload)
   if((op.type=="note.update" && canRebase) || (target!=id && op.type in listOf("note.flags","note.restore"))) dao.putOutbox(queued.copy(noteId=target,payload=Json.encodeToString(op.copy(noteId=target,baseVersion=if(op.type=="note.update" && canRebase) version else op.baseVersion))))
  }
  if(remaining.isNotEmpty()) { repository.putNote(note.copy(id=target,conflictOf=if(target!=id) id else note.conflictOf,serverRev=if(canRebase) version else note.serverRev,pending=true)); if(target!=id) repository.putNote(note.copy(pending=false)) }
  else if(effective.note!=null) repository.putNote(noteFromWire(effective.note).copy(pending=false))
  else repository.putNote(note.copy(body=effective.body?:note.body,title=titleOf(effective.body?:note.body),serverRev=version,pending=false,syncError=null))
 }
 private suspend fun refreshOriginals(api:ApiClient) {
  val dao=repository.dao
  for(refresh in dao.refreshNotes()) {
   if(dao.outbox().any { queued -> queued.noteId==refresh.id || refresh.id in Json.decodeFromString<OutboxOperation>(queued.payload).affectedNoteIds }) continue
   val original=try { noteFromWire(api.get("/api/v1/notes/${refresh.id}")) } catch(error:ApiException) { if(error.status==404) { dao.removeRefresh(refresh.id);continue };throw error }
   ownedTransaction { if(dao.outbox().none { it.noteId==refresh.id }) { repository.putNote(original);dao.removeRefresh(refresh.id) } }
  }
 }
 private suspend fun applyPull(item:PullItem) {
  val dao=repository.dao; val data=item.data
  when(item.kind) {
   "note" -> { val note=noteFromWire(data); if(dao.tombstone(note.id)==null && dao.outbox().none { queued -> queued.noteId==note.id || note.id in Json.decodeFromString<OutboxOperation>(queued.payload).affectedNoteIds }) repository.putNote(note) }
   "tombstone" -> {
    val id=data.getValue("id").jsonPrimitive.content; val kind=data["kind"]?.jsonPrimitive?.content?:"note"
    if(kind=="note") { dao.putTombstone(TombstoneEntity(id,System.currentTimeMillis())); if(dao.outbox().none { queued -> queued.noteId==id || id in Json.decodeFromString<OutboxOperation>(queued.payload).affectedNoteIds }) { dao.remove(id);dao.clearLabels(id) } else dao.get(id)?.note()?.let { repository.putNote(it.copy(trashed=true,syncError="deleted_on_server")) } }
    else dao.removeOutput(kind,id)
   }
   "suggestion","annotation" -> { val id=data.getValue("id").jsonPrimitive.content; dao.putOutput(PluginOutputEntity(item.kind,id,data["note_id"]?.jsonPrimitive?.contentOrNull,data.toString())) }
  }
 }
 private suspend fun uploadAttachments(api:ApiClient) {AttachmentUploader(repository,api.config,api,configProvider).uploadAll()}

}
class ChangeWaiter(private val configProvider:suspend ()->AuthConfig?,private val repository:NotesRepository,private val engine:SyncEngine) {
 constructor(auth:AuthStore,repository:NotesRepository,engine:SyncEngine):this({auth.config.first()},repository,engine)
 suspend fun run() {
  while(currentCoroutineContext().isActive) {
   val config=configProvider()?:return
   if(!config.ownsReplica(repository.replicaId)) return
   try {
    val state=repository.dao.state()
    if(state!=null && state.serverUrl.isNotEmpty() && state.serverUrl!=config.serverUrl.trimEnd('/')) return
    if(state==null || state.cursor.isEmpty()) { engine.sync();delay(1000);continue }
    val response=ApiClient(config).waitForChanges(state.cursor,state.pluginsRev)
    if(response["changed"]?.jsonPrimitive?.booleanOrNull==true) engine.sync()
   }
   catch(cancelled:CancellationException) { throw cancelled }
   catch(error:Exception) { if(error is ApiException && error.status==401) { engine.sync(); return }; delay(30_000) }
  }
 }
}

/** Test gates hold actual private write/commit boundaries; production adds no suspension here. */
data class PluginPublicationHooks(val beforeWrite:suspend ()->Unit={},val beforeCommit:suspend ()->Unit={},val afterCommit:suspend ()->Unit={})
data class CommittedPluginPublication(val owner:PluginPublicationTicket,val publication:PluginPublication?)

/** An emitted epoch is never an authority: read current committed state and credentials together. */
suspend fun readCommittedPluginPublication(repository:NotesRepository,config:AuthConfig?,fence:PluginPublicationFence=processPluginPublications,configProvider:suspend ()->AuthConfig?):CommittedPluginPublication?=repository.db.withTransaction {
 if(configProvider()!=config || config!=null && !config.ownsReplica(repository.replicaId)) return@withTransaction null
 val state=repository.dao.state()
 if(configProvider()!=config) return@withTransaction null
 val matches=config!=null && config.ownsReplica(repository.replicaId) && (!config.hosted || state?.replicaId==config.replicaId) && state!=null && state.serverUrl.trimEnd('/')==config.serverUrl.trimEnd('/')
 val owner=fence.transitionOwner(config ?: "signed-out",config?.serverUrl.orEmpty(),if(matches) state!!.epoch else "")
 if(matches) seedCommittedPluginPublication(fence,owner,state!!)
 CommittedPluginPublication(owner,fence.current(owner))
}
private fun seedCommittedPluginPublication(fence:PluginPublicationFence,owner:PluginPublicationTicket,state:SyncStateEntity) {
 val manifest=runCatching {Json.parseToJsonElement(state.manifest).jsonObject}.getOrNull()
 val catalog=runCatching {Json.parseToJsonElement(state.catalog).jsonObject}.getOrNull()
 if(manifest!=null && catalog!=null) fence.publish(owner,manifest,catalog)
}
private suspend fun writePluginPublication(repository:NotesRepository,config:AuthConfig,epoch:String,configProvider:suspend ()->AuthConfig?,fence:PluginPublicationFence,hooks:PluginPublicationHooks,stage:(PluginPublicationTicket)->PluginPublicationStage?):PluginPublication? {
 val pending=repository.db.withTransaction {
  val current=repository.dao.state() ?: return@withTransaction null
  if(configProvider()!=config || !config.ownsReplica(repository.replicaId) || config.hosted && current.replicaId!=config.replicaId || current.serverUrl.trimEnd('/')!=config.serverUrl.trimEnd('/') || current.epoch!=epoch) return@withTransaction null
  val owner=fence.transitionOwner(config,config.serverUrl,current.epoch)
  seedCommittedPluginPublication(fence,owner,current)
  val candidate=stage(owner) ?: return@withTransaction null
  hooks.beforeWrite();currentCoroutineContext().ensureActive()
  check(configProvider()==config) {"Plugin owner changed before the write."}
  repository.dao.putState(current.copy(manifest=candidate.publication.manifest.toString(),catalog=candidate.publication.catalog.toString()))
  hooks.beforeCommit();currentCoroutineContext().ensureActive()
  check(configProvider()==config) {"Plugin owner changed before commit."}
  candidate
 } ?: return null
 // withTransaction returned: the staged bytes are now durable, not a future public snapshot.
 hooks.afterCommit();currentCoroutineContext().ensureActive()
 return repository.db.withTransaction {
  val current=repository.dao.state() ?: return@withTransaction null
  if(configProvider()!=config || !config.ownsReplica(repository.replicaId) || config.hosted && current.replicaId!=config.replicaId || current.serverUrl.trimEnd('/')!=config.serverUrl.trimEnd('/') || current.epoch!=epoch || current.manifest!=pending.publication.manifest.toString() || current.catalog!=pending.publication.catalog.toString()) return@withTransaction null
  fence.commit(pending)
 }
}
suspend fun persistPluginSnapshot(repository:NotesRepository,config:AuthConfig,epoch:String,manifest:JsonObject,catalog:JsonObject,fence:PluginPublicationFence=processPluginPublications,hooks:PluginPublicationHooks=PluginPublicationHooks(),configProvider:suspend ()->AuthConfig?):PluginPublication? =
 writePluginPublication(repository,config,epoch,configProvider,fence,hooks) {owner -> fence.stage(owner,manifest,catalog)}
suspend fun acknowledgePluginSnapshot(repository:NotesRepository,config:AuthConfig,epoch:String,row:JsonObject,fence:PluginPublicationFence=processPluginPublications,hooks:PluginPublicationHooks=PluginPublicationHooks(),configProvider:suspend ()->AuthConfig?):PluginPublication? =
 writePluginPublication(repository,config,epoch,configProvider,fence,hooks) {owner -> fence.stageAcknowledgement(owner,row)}
