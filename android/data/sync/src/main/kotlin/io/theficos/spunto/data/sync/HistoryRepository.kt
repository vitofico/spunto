package io.theficos.spunto.data.sync

import androidx.room.withTransaction
import io.theficos.spunto.data.local.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.time.Instant

@Serializable data class ServerRevision(val version:Int,val body:String,@SerialName("change_kind") val changeKind:String,@SerialName("changed_by") val changedBy:String?=null,@SerialName("changed_at") val changedAt:String)
sealed interface HistoryRefreshResult {
 data class Refreshed(val revisions:Int):HistoryRefreshResult
 data class Cached(val reason:String):HistoryRefreshResult
 data object NeedsSignIn:HistoryRefreshResult
}
/** Read-only hydration: history never replaces a note or acknowledges an operation. */
class HistoryRepository(private val repository:NotesRepository,private val configProvider:suspend ()->AuthConfig?,private val apiFactory:(AuthConfig)->ApiClient={ ApiClient(it) }) {
 constructor(repository:NotesRepository,auth:AuthStore,apiFactory:(AuthConfig)->ApiClient={ ApiClient(it) }):this(repository,{ auth.config.first() },apiFactory)
 private val mutex=Mutex()
 private val json=Json { ignoreUnknownKeys=true }
 suspend fun refresh(noteId:String):HistoryRefreshResult=withContext(Dispatchers.IO) { mutex.withLock {
  val config=configProvider() ?: return@withLock HistoryRefreshResult.Cached("signed_out")
  if(!config.ownsReplica(repository.replicaId)) return@withLock HistoryRefreshResult.Cached("different_identity")
  val dao=repository.dao
  val scope=dao.state() ?: return@withLock HistoryRefreshResult.Cached("not_synced")
  val server=config.serverUrl.trimEnd('/')
  val snapshot=dao.get(noteId) ?: return@withLock HistoryRefreshResult.Cached("not_found")
  if(scope.serverUrl!=server || scope.epoch.isEmpty() || snapshot.serverRev<=0 || dao.tombstone(noteId)!=null) return@withLock HistoryRefreshResult.Cached("not_synced")
  try {
   val api=apiFactory(config).guarded {configProvider()==config && config.ownsReplica(repository.replicaId)}
   val response=json.decodeFromJsonElement<List<ServerRevision>>(api.request("/api/v1/notes/"+noteId+"/revisions"))
   require(response.all { it.version>0 } && response.map { it.version }.distinct().size==response.size) { "invalid_history" }
   val rows=response.map { revision -> ServerRevisionEntity(server,scope.epoch,noteId,revision.version,revision.body,Instant.parse(revision.changedAt).toEpochMilli(),revision.changedBy,revision.changeKind) }
   val maximum=rows.maxOfOrNull { it.version } ?: 0
   // History does not carry an epoch. Check it after the read before caching restored-server rows.
   val epoch=api.meta()["sync_epoch"]?.jsonPrimitive?.contentOrNull
   if(epoch!=scope.epoch || maximum<snapshot.serverRev) return@withLock HistoryRefreshResult.Cached("stale_history")
   val currentConfig=configProvider()
   if(currentConfig!=config) return@withLock HistoryRefreshResult.Cached("credentials_changed")
   repository.db.withTransaction {
    val currentScope=dao.state();val current=dao.get(noteId)
    if(currentScope==null || currentScope.serverUrl!=server || currentScope.epoch!=scope.epoch || current==null || dao.tombstone(noteId)!=null || current.serverRev>maximum) return@withTransaction HistoryRefreshResult.Cached("stale_history")
    dao.clearServerRevisions(server,scope.epoch,noteId)
    dao.putServerRevisions(rows)
    dao.putHistoryCache(HistoryCacheEntity(server,scope.epoch,noteId,System.currentTimeMillis(),maximum))
    HistoryRefreshResult.Refreshed(rows.size)
   }
  } catch(error:CancellationException) { throw error }
  catch(error:ApiException) { if(error.status==401) HistoryRefreshResult.NeedsSignIn else HistoryRefreshResult.Cached(error.code) }
  catch(error:Exception) { HistoryRefreshResult.Cached(if(configProvider()!=config) "credentials_changed" else "unavailable") }
 } }
}
