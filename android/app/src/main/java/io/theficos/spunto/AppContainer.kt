package io.theficos.spunto

import android.app.Application
import android.content.Context
import io.theficos.spunto.data.local.*
import io.theficos.spunto.data.sync.*
import io.theficos.spunto.core.plugins.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import io.theficos.spunto.core.model.processPluginPublications
import android.net.ConnectivityManager
import android.net.Network

class SpuntoApplication : Application(), androidx.work.Configuration.Provider {
 override val workManagerConfiguration: androidx.work.Configuration get() = androidx.work.Configuration.Builder().setMinimumLoggingLevel(android.util.Log.WARN).build()
 val container by lazy { AppContainer(this) }
 override fun onCreate() { super.onCreate();container.scope.launch { container.scheduler.periodic() }; val receiver=object:android.content.BroadcastReceiver() {override fun onReceive(context:Context,intent:android.content.Intent) {container.appLock.lock()} }; val filter=android.content.IntentFilter(android.content.Intent.ACTION_SCREEN_OFF); if(android.os.Build.VERSION.SDK_INT>=33) registerReceiver(receiver,filter,Context.RECEIVER_NOT_EXPORTED) else { @Suppress("DEPRECATION") registerReceiver(receiver,filter) } }
}
/** The process selects immutable replicas; an editor always keeps its original repository. */
class AppContainer(val context:Context) {
 val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
 val auth=AuthStore(context)
 internal val appLock=AppLock(context)
 private val connectivity=context.getSystemService(ConnectivityManager::class.java)
 val connected=MutableStateFlow(connectivity.activeNetwork!=null)
 val scheduler by lazy {SyncScheduler(context)}
 private val databases=ReplicaDatabaseCache(context)
 private var generation=0L
 private val active=MutableStateFlow(newReplica(runBlocking(Dispatchers.IO) {auth.selection.first()}))
 val replicas:kotlinx.coroutines.flow.StateFlow<ReplicaContainer> = active
 private fun newReplica(selection:AuthSelection)=ReplicaContainer(context,selection.replicaId,++generation,auth,appLock,connected,scheduler,databases)
 val current get()=active.value
 val database get()=current.database
 val repository get()=current.repository
 val recordings get()=current.recordings
 val recordingRecovery get()=current.recordingRecovery
 val sync get()=current.sync
 val manifest get()=current.manifest
 val preferences get()=current.preferences
 init {
  scope.launch {auth.selection.collect {selection -> if(selection.replicaId!=active.value.replicaId) {val previous=active.value;previous.retire();io.theficos.spunto.plugins.quickcapture.CaptureSurfaces.cacheSlip(context,null,null);active.value=newReplica(selection)};scheduler.periodic()}}
  connectivity.registerDefaultNetworkCallback(object:ConnectivityManager.NetworkCallback() {override fun onAvailable(network:Network) {connected.value=true};override fun onLost(network:Network) {connected.value=false}})
 }
}
class ReplicaContainer internal constructor(val context:Context,val replicaId:String,val generation:Long,val auth:AuthStore,internal val appLock:AppLock,val connected:MutableStateFlow<Boolean>,val scheduler:SyncScheduler,private val databases:ReplicaDatabaseCache) {
 val key get()="$replicaId:$generation"
 val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
 val database by lazy {databases.get(replicaDatabaseName(replicaId))}
 val repository by lazy {NotesRepository(database,replicaId) {scope.launch {scheduler.schedule()}}}
 fun privateDirectory(name:String)=java.io.File(context.filesDir,if(replicaId=="legacy") name else "replicas/$replicaId/$name").apply {mkdirs()}
 val recordingDirectory by lazy {privateDirectory("recordings")}
 val recordings by lazy {RecordingRepository(repository,recordingDirectory)}
 val recordingRecovery by lazy {scope.async {recordings.recover()}}
 val sync by lazy {SyncEngine(repository,auth)}
 val manifest=ManifestCache(context,namespace=if(replicaId=="legacy") "" else replicaId)
 val preferences=context.getSharedPreferences(if(replicaId=="legacy") "appearance" else "appearance-$replicaId",Context.MODE_PRIVATE)
 init {
  scope.launch {
   var previous:String?="initial"
   combine(repository.notes,repository.outputs(),database.dao().observeState(),auth.config,appLock.state) {notes,outputs,state,config,_ -> Pair(Triple(notes,outputs,state),config)}.collect {(replica,config) ->
    val (notes,outputs,_)=replica
    val committed=readCommittedPluginPublication(repository,config) {auth.config.first()}
    manifest.withCaptureSurfacePublication(notes,outputs,updateManifest={
     if(committed!=null) {
      val raw=manifest.value.toString()
      if(raw!=previous) {io.theficos.spunto.plugins.quickcapture.CaptureSurfaces.apply(context,manifest.enabled("quick-capture"),manifest.enabled("dictation"));if(!manifest.enabled("dictation")) context.getSystemService(android.content.pm.ShortcutManager::class.java).removeDynamicShortcuts(listOf("dictate"));previous=raw}
     }
    },cacheSlip={id,text,writtenAt ->io.theficos.spunto.plugins.quickcapture.CaptureSurfaces.cacheSlip(context,id,text,writtenAt)})
   }
  }
 }
 fun retire() {scope.cancel()}
}
