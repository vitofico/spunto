package io.theficos.spunto

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.theficos.spunto.plugins.quickcapture.CaptureSurfaces
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class AppLock(context: Context) {
 private val context = context.applicationContext
 private val prefs = this.context.getSharedPreferences("device-surfaces", Context.MODE_PRIVATE)
 private val policy = AppLockState(prefs.getBoolean("app-lock-enabled", false))
 private val main = Handler(Looper.getMainLooper())
 private val mutable = MutableStateFlow(policy.snapshot)
 val state = mutable.asStateFlow()
 private fun publish() { mutable.value = policy.snapshot }
 fun started(owner: Any) { policy.started(owner); publish() }
 fun stopped(owner: Any, changing: Boolean, credential: Long?) {
  val stopped = policy.stopped(owner, changing, credential); publish()
  main.post { policy.settled(stopped, credential); publish() }
 }
 fun begin(): Long = policy.begin().also { publish() }
 fun complete(expected: Long): Boolean = policy.complete(expected).also { publish() }
 fun cancel(expected: Long) { policy.cancel(expected); publish() }
 fun lock() { policy.lock(); publish() }
 fun configure(enabled: Boolean) {
  check(prefs.edit().putBoolean("app-lock-enabled", enabled).commit()) { "Couldn't save the phone lock setting." }
  policy.configure(enabled); publish()
  if(enabled) CaptureSurfaces.cacheSlip(context, null, null)
  CaptureSurfaces.refreshWidgets(context)
 }
}
