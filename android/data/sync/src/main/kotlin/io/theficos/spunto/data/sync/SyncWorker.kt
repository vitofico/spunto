package io.theficos.spunto.data.sync
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.work.*
import io.theficos.spunto.data.local.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
class SyncWorker(context:Context,parameters:WorkerParameters):CoroutineWorker(context,parameters) {
 override suspend fun getForegroundInfo():ForegroundInfo {
  val manager=applicationContext.getSystemService(NotificationManager::class.java)
  manager.createNotificationChannel(NotificationChannel("sync","Note sync",NotificationManager.IMPORTANCE_LOW))
  return ForegroundInfo(1,Notification.Builder(applicationContext,"sync").setSmallIcon(android.R.drawable.stat_notify_sync).setContentTitle("Syncing notes").setOngoing(true).build())
 }
 override suspend fun doWork():Result {
  val auth=AuthStore(applicationContext)
  val selected=auth.selection.first();val config=selected.config ?: return Result.success()
  if(!workerIdentityMatches(config,inputData.getString("replica"),inputData.getString("credential"))) return Result.success()
  if(selected.replicaId!=config.replicaId) return Result.success()
  val db=SpuntoDatabase.open(applicationContext,replicaDatabaseName(selected.replicaId))
  return try { when(SyncEngine(NotesRepository(db,selected.replicaId),auth).sync()) { is SyncStatus.Waiting -> Result.retry(); else -> Result.success() } } finally { db.close() }
 }
}
/** Persist the debounce before enqueueing expedited work, which Android cannot delay. */
class SyncKickWorker(context:Context,parameters:WorkerParameters):CoroutineWorker(context,parameters) {
 override suspend fun doWork():Result {
  val config=AuthStore(applicationContext).config.first() ?: return Result.success()
  if(workerIdentityMatches(config,inputData.getString("replica"),inputData.getString("credential"))) SyncScheduler(applicationContext).expedite(config)
  return Result.success()
 }
}
fun workerCredential(config:AuthConfig)=replicaHash(listOf(config.replicaId,config.deviceId,config.token))
fun workerIdentityMatches(config:AuthConfig,replica:String?,credential:String?):Boolean =
 if(replica==null && credential==null) !config.hosted else replica==config.replicaId && credential==workerCredential(config)
class SyncScheduler(private val context:Context) {
 private suspend fun ticket(expected:AuthConfig?=null):Data? {
  val config=AuthStore(context).config.first() ?: return null
  if(expected!=null && config!=expected) return null
  return workDataOf("replica" to config.replicaId,"credential" to workerCredential(config))
 }
 private val manager=WorkManager.getInstance(context.applicationContext)
 private val constraints=Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
 suspend fun schedule() {val data=ticket() ?: return;manager.enqueueUniqueWork("sync-debounce",ExistingWorkPolicy.REPLACE,OneTimeWorkRequestBuilder<SyncKickWorker>().setInputData(data).setConstraints(constraints).setInitialDelay(2,TimeUnit.SECONDS).build()) }
 suspend fun expedite(expected:AuthConfig?=null) {val data=ticket(expected) ?: return;manager.enqueueUniqueWork("sync-now",ExistingWorkPolicy.REPLACE,OneTimeWorkRequestBuilder<SyncWorker>().setInputData(data).setConstraints(constraints).setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST).setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build()) }
 suspend fun periodic() {val data=ticket() ?: return;manager.enqueueUniquePeriodicWork("sync-periodic",ExistingPeriodicWorkPolicy.UPDATE,PeriodicWorkRequestBuilder<SyncWorker>(15,TimeUnit.MINUTES).setInputData(data).setConstraints(constraints).setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build()) }
}
