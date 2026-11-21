package io.theficos.spunto

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.work.*
import io.theficos.spunto.data.sync.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.util.concurrent.TimeUnit

internal const val ActivityRoute = "shared-activity"
private const val channel = "shared-activity"
private const val alertId = 24
private val delivery = Mutex()

/** Rows can commit out of timestamp order. Remember the IDs actually seen inside the server's 50-row window.
 * The lower bound prevents old rows resurfacing as new when earlier grants are revoked.
 */
internal data class ActivityCursor(val floor: Long = -1, val receipts: Map<String, Long> = emptyMap()) {
    fun unseen(event: SharedEvent) = event.createdAt >= floor && event.id !in receipts
    fun advance(events: List<SharedEvent>): ActivityCursor {
        val nextFloor = if (events.size >= 50) maxOf(floor, events.minOf { it.createdAt }) else floor
        return ActivityCursor(nextFloor, (receipts + events.associate { it.id to it.createdAt }).filterValues { it >= nextFloor })
    }
}

internal class SharedActivityAlerts(private val context: Context, private val config: AuthConfig) {
    private val prefs = context.getSharedPreferences("shared-activity-${config.replicaId}", Context.MODE_PRIVATE)
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val auth = AuthStore(context)
    val api = pluginApi(config, context.resources).guarded { auth.config.first() == config }
    val enabled get() = prefs.getBoolean("enabled", false)
    val allowed get() = manager.areNotificationsEnabled() &&
        (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
        manager.getNotificationChannel(channel)?.importance != NotificationManager.IMPORTANCE_NONE

    private fun save(cursor: ActivityCursor) {
        val receipts = cursor.receipts.map { (id, timestamp) -> "$timestamp:$id" }.toSet()
        prefs.edit().putBoolean("enabled", true).putLong("floor", cursor.floor).putStringSet("receipts", receipts).apply()
    }

    suspend fun configure(enable: Boolean) = delivery.withLock {
        if (enable) {
            val baseline = api.sharedActivity()
            if (auth.config.first() != config) return@withLock
            val cursor = ActivityCursor().advance(baseline.notifications)
            save(cursor)
            schedule(context, config)
        } else {
            prefs.edit().clear().apply()
            if (auth.config.first() == config) WorkManager.getInstance(context).cancelUniqueWork(channel)
            manager.cancel(config.replicaId, alertId)
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun poll() = delivery.withLock {
        if (!enabled || !allowed || auth.config.first() != config) return@withLock
        val feed = api.sharedActivity()
        val events = feed.notifications.filter { it.workspaceId == config.workspaceId }
        if (auth.config.first() != config || !allowed) return@withLock
        val receipts = prefs.getStringSet("receipts", emptySet()).orEmpty().associate { it.substringAfter(':') to it.substringBefore(':').toLong() }
        val cursor = ActivityCursor(prefs.getLong("floor", -1), receipts)
        if (events.none { !it.read }) manager.cancel(config.replicaId, alertId)
        if (events.any { !it.read && cursor.unseen(it) }) {
            manager.createNotificationChannel(NotificationChannel(channel, context.getString(R.string.shared_activity_title), NotificationManager.IMPORTANCE_LOW))
            val intent = Intent(context, MainActivity::class.java).setAction(ActivityRoute).putExtra("replica", config.replicaId)
            val pending = PendingIntent.getActivity(context, alertId, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notification = Notification.Builder(context, channel)
                .setSmallIcon(R.drawable.ic_shared_activity)
                .setContentTitle(context.getString(R.string.shared_activity_title))
                .setContentText(context.getString(R.string.shared_activity_alert))
                .setOnlyAlertOnce(true).setAutoCancel(true).setVisibility(Notification.VISIBILITY_PRIVATE)
                .setContentIntent(pending).build()
            manager.notify(config.replicaId, alertId, notification)
        }
        val next = cursor.advance(events)
        save(next)
    }

    companion object {
        suspend fun onSelection(context: Context, config: AuthConfig?) = delivery.withLock {
            if (AuthStore(context).config.first() != config) return@withLock
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.activeNotifications.filter { it.id == alertId && it.tag != config?.replicaId }.forEach { manager.cancel(it.tag, alertId) }
            if (config?.hosted == true && SharedActivityAlerts(context, config).enabled) schedule(context, config)
            else WorkManager.getInstance(context).cancelUniqueWork(channel)
        }
        private fun schedule(context: Context, config: AuthConfig) {
            val request = PeriodicWorkRequestBuilder<SharedActivityWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInputData(workDataOf("replica" to config.replicaId, "credential" to workerCredential(config))).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(channel, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}

class SharedActivityWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val config = AuthStore(applicationContext).config.first() ?: return Result.success()
        if (!config.hosted || !workerIdentityMatches(config, inputData.getString("replica"), inputData.getString("credential"))) return Result.success()
        return try {
            SharedActivityAlerts(applicationContext, config).poll()
            Result.success()
        } catch (error: ApiException) {
            if (error.status in setOf(401, 403, 404)) applicationContext.getSystemService(NotificationManager::class.java).cancel(config.replicaId, alertId)
            if (error.status >= 500 || error.status == 429) Result.retry() else Result.success()
        } catch (_: IOException) { Result.retry() }
        catch (_: IllegalStateException) { Result.success() } // The active credential changed during the request.
    }
}
