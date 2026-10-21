package io.theficos.spunto

import android.app.KeyguardManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import io.theficos.spunto.core.design.SpuntoTheme
import kotlinx.coroutines.launch

internal class LockPromptState: ViewModel() {
 var token: Long? = null
 var setting: Boolean? = null
 var awaitingCredential = false
 var credentialToken: Long? = null
 var biometricActive=false
 var cancelling=false
 var transitioning=false
}

/** One entry gate for launcher, shortcuts, shares and selection capture. */
abstract class LockedActivity: FragmentActivity() {
 private val appLock get() = (application as SpuntoApplication).container.appLock
 private lateinit var promptState: LockPromptState
 private lateinit var prompt: BiometricPrompt
 private var screen by mutableStateOf<@Composable () -> Unit>({})
 private var error by mutableStateOf("")
 private var resumed = false
 private var lastUnlockedRevision: Long? = null
 private val activityOwner=Any()
 protected val unlocked get() = appLock.state.value.unlocked
 internal val lockMessage get()=error
 private val credential = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
  val expected=promptState.credentialToken
  promptState.credentialToken=null
  if(current(expected)) {
   promptState.awaitingCredential = false
   if(result.resultCode == RESULT_OK) succeed(expected!!) else fail(expected!!,"Unlock cancelled. Your notes stay locked.")
  }
 }
 override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState)
  enableEdgeToEdge() // Android 15+ forces it at targetSdk 35; doing it everywhere keeps older phones the same.
  promptState = ViewModelProvider(this)[LockPromptState::class.java]
  prompt = buildPrompt(promptState.token)
  lifecycleScope.launch { appLock.state.collect { state ->
   if(state.enabled) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
   if(state.unlocked && resumed && lastUnlockedRevision != state.revision) { lastUnlockedRevision = state.revision; onUnlocked() }
  }}
  setContent {
   val replica by (application as SpuntoApplication).container.replicas.collectAsState()
   val state by appLock.state.collectAsState()
   var mounted by remember { mutableStateOf(false) }
   if(state.unlocked) mounted = true
   val focus = LocalFocusManager.current
   val keyboard = LocalSoftwareKeyboardController.current
   LaunchedEffect(state.unlocked) { if(!state.unlocked) { focus.clearFocus(force = true); keyboard?.hide() } }
   Box(Modifier.fillMaxSize()) {
    // Retaining composition preserves unsaved forms and the active editor lease.
    // A modal secure window owns input, while this tree draws no private pixels
    // and publishes no accessibility descendants during a lock.
    if(mounted) Box(if(state.unlocked) Modifier.fillMaxSize() else Modifier.fillMaxSize().graphicsLayer { alpha = 0f }.clearAndSetSemantics {}) { screen() }
    if(!state.unlocked) SpuntoTheme(namespace=replica.replicaId.takeUnless {it=="legacy"}.orEmpty()) {
     Surface(Modifier.fillMaxSize()) {}
     Dialog(onDismissRequest = {}, properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false, usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
      val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
      val darkIcons=io.theficos.spunto.core.design.useDarkSystemBarIcons(MaterialTheme.colorScheme.background)
      SideEffect { dialogWindow?.let {dialog ->
       dialog.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
       androidx.core.view.WindowCompat.getInsetsController(dialog,dialog.decorView).apply {isAppearanceLightStatusBars=darkIcons;isAppearanceLightNavigationBars=darkIcons}
      } }
      Surface(Modifier.fillMaxSize()) { Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center) {
       Text("Spunto is locked", style = MaterialTheme.typography.headlineLarge)
       Spacer(Modifier.height(16.dp)); Text("Unlock with a strong biometric or this phone's PIN, pattern or password.")
       if(error.isNotEmpty()) Text(error)
       Spacer(Modifier.height(20.dp)); Button(onClick = { authenticate(null) }) { Text("Unlock Spunto") }
       TextButton(onClick = { authenticate(null, credentialOnly = true) }) { Text("Use screen lock") }
       TextButton(onClick = { finish() }) { Text("Close") }
      }}
     }
     BackHandler { finish() }
    }
   }
  }
 }
 protected fun setProtectedContent(content: @Composable () -> Unit) { screen = content }
 protected open fun onUnlocked() {}
 override fun onStart() {
  appLock.started(activityOwner)
  super.onStart()
  val keyguard = getSystemService(KeyguardManager::class.java)
  if(appLock.state.value.enabled && keyguard.isDeviceLocked) appLock.lock()
 }
 override fun onResume() { super.onResume(); resumed = true; if(unlocked) onUnlocked() }
 override fun onPause() { resumed = false; super.onPause() }
 override fun onStop() {
  val expectedCredential=promptState.awaitingCredential && promptState.token!=null
  appLock.stopped(activityOwner, isChangingConfigurations, promptState.token.takeIf {expectedCredential})
  if(!isChangingConfigurations && !expectedCredential) cancelPrompt()
  super.onStop()
 }
 override fun onDestroy() {
  if(!isChangingConfigurations) cancelPrompt()
  super.onDestroy()
 }
 internal fun setPhoneLock(enabled: Boolean) { authenticate(enabled) }
 private fun current(expected:Long?)=expected!=null && promptState.token==expected
 private fun buildPrompt(expected:Long?)=BiometricPrompt(this,ContextCompat.getMainExecutor(this),object:BiometricPrompt.AuthenticationCallback() {
  override fun onAuthenticationSucceeded(result:BiometricPrompt.AuthenticationResult) {
   if(!current(expected)) return
   promptState.biometricActive=false
   if(promptState.cancelling) finishCancellation(expected!!) else succeed(expected!!)
  }
  override fun onAuthenticationFailed() {if(current(expected) && !promptState.cancelling) error="Not recognized. Try again or use your screen lock."}
  override fun onAuthenticationError(code:Int,message:CharSequence) {
   if(!current(expected)) return
   promptState.biometricActive=false
   if(promptState.cancelling) {finishCancellation(expected!!);return}
   if(code==BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
    val setting=promptState.setting
    cancelPrompt()
    promptState.transitioning=true
    window.decorView.post {promptState.transitioning=false;authenticate(setting,credentialOnly=true)}
   } else fail(expected!!,"Unlock was not completed. Try again or use your screen lock.")
  }
 })
 private fun finishCancellation(expected:Long) {
  // AndroidX keeps one callback per live prompt. Do not attach a new callback
  // until the old prompt's terminal event has fully returned to the framework.
  window.decorView.post {if(current(expected) && promptState.cancelling) {promptState.token=null;promptState.setting=null;promptState.cancelling=false;promptState.awaitingCredential=false}}
 }
 private fun cancelPrompt() {
  promptState.token?.let(appLock::cancel)
  if(promptState.biometricActive) {promptState.cancelling=true;prompt.cancelAuthentication()}
  else {promptState.token=null;promptState.setting=null;promptState.awaitingCredential=false;promptState.cancelling=false}
 }
 private fun authenticate(setting: Boolean?, credentialOnly: Boolean = false) {
  if(!resumed || isFinishing || promptState.token!=null || promptState.transitioning || promptState.cancelling) return
  val keyguard = getSystemService(KeyguardManager::class.java)
  if(!keyguard.isDeviceSecure) { report("Set a phone PIN, pattern or password in Android Settings first."); return }
  promptState.token = appLock.begin(); promptState.setting = setting; error = ""
  val expected=promptState.token!!
  prompt=buildPrompt(expected)
  val strong = BiometricManager.Authenticators.BIOMETRIC_STRONG
  if(credentialOnly && Build.VERSION.SDK_INT < 30) {screenCredential();return}
  val authenticators = if(credentialOnly) BiometricManager.Authenticators.DEVICE_CREDENTIAL else strong
  promptState.awaitingCredential=credentialOnly
  if(BiometricManager.from(this).canAuthenticate(authenticators) != BiometricManager.BIOMETRIC_SUCCESS) { screenCredential(); return }
  val info = BiometricPrompt.PromptInfo.Builder().setTitle("Unlock Spunto").setSubtitle("Use this phone's biometric or screen lock").setAllowedAuthenticators(authenticators)
  if(!credentialOnly) info.setNegativeButtonText("Use screen lock")
  try { promptState.biometricActive=true;prompt.authenticate(info.build()) } catch(_: Exception) { promptState.biometricActive=false;fail(expected,"Authentication is unavailable. Use your screen lock.") }
 }
 @Suppress("DEPRECATION")
 private fun screenCredential() {
  val expected=promptState.token ?: return
  val intent = getSystemService(KeyguardManager::class.java).createConfirmDeviceCredentialIntent("Unlock Spunto", "Use this phone's PIN, pattern or password")
  if(intent == null) { fail(expected,"Set a secure screen lock in Android Settings first."); return }
  promptState.awaitingCredential = true
  promptState.credentialToken=expected
  try { credential.launch(intent) } catch(_: Exception) { promptState.awaitingCredential = false;promptState.credentialToken=null; fail(expected,"Screen lock authentication is unavailable.") }
 }
 private fun succeed(expected:Long) {
  if(!current(expected)) return
  if(!appLock.complete(expected)) { promptState.token = null;promptState.setting=null;promptState.awaitingCredential=false; error = "Unlock expired. Try again."; return }
  promptState.awaitingCredential=false
  promptState.token = null
  try { promptState.setting?.let(appLock::configure); promptState.setting = null; error = "" }
  catch(_: Exception) { appLock.lock(); error = "Couldn't save the phone lock setting." }
 }
 private fun report(message: String) {error=message;if(unlocked) Toast.makeText(this,message,Toast.LENGTH_LONG).show()}
 private fun fail(expected:Long,message: String) { if(!current(expected)) return; appLock.cancel(expected); promptState.token = null; promptState.setting = null;promptState.awaitingCredential=false; report(message) }
}
