package io.theficos.spunto

import android.app.KeyguardManager
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.os.Bundle
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.theficos.spunto.plugins.quickcapture.CaptureSurfaces
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/** Effectful lock/PIN tests run only on the explicitly named disposable AVD. */
@RunWith(AndroidJUnit4::class)
class AppLockTest {
 @get:org.junit.Rule val timeout=org.junit.rules.Timeout(5,TimeUnit.MINUTES)
 private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
 private val app get()=instrumentation.targetContext.applicationContext as SpuntoApplication
 private lateinit var authority:LocalFixtureAuthority
 private fun shell(command:String):String=ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use {it.readText()}
 @Before fun disposableAuthority() {
  assumeTrue(InstrumentationRegistry.getArguments().getString("spuntoAppLockDisposable")=="yes")
  check(shell("getprop ro.boot.qemu.avd_name").trim()=="SpuntoBiometric") {"App lock tests require the dedicated disposable AVD."}
  authority=runBlocking {LocalFixtureAuthority.require(app.container)}
  instrumentation.uiAutomation.serviceInfo=instrumentation.uiAutomation.serviceInfo.apply {flags=flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS}
 }
 private fun waitFor(description:String="the expected lock screen state",test:()->Boolean) {
  val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20)
  while(System.nanoTime()<deadline) {if(test()) return;Thread.sleep(50)}
  check(test()) {"Timed out waiting for $description."}
 }
 private fun nodes(node:AccessibilityNodeInfo?):List<AccessibilityNodeInfo> {
  if(node==null) return emptyList()
  return listOf(node)+(0 until node.childCount).flatMap {nodes(node.getChild(it))}
 }
 private fun authenticationNodes():List<AccessibilityNodeInfo> =
  (listOfNotNull(instrumentation.uiAutomation.rootInActiveWindow)+instrumentation.uiAutomation.windows.mapNotNull {it.root})
   .flatMap {nodes(it)}.filter {it.packageName?.toString() in setOf("com.android.systemui","com.android.settings")}
 private fun performClick(node:AccessibilityNodeInfo) {
  var target:AccessibilityNodeInfo?=node
  while(target!=null && !target.isClickable) target=target.parent
  check(target?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true)
 }
 private fun click(text:String) {
  waitFor {nodes(instrumentation.uiAutomation.rootInActiveWindow).any {it.text?.toString()==text}}
  val node=nodes(instrumentation.uiAutomation.rootInActiveWindow).first {it.text?.toString()==text}
  performClick(node)
 }
 private fun clickAuthentication(matches:(AccessibilityNodeInfo)->Boolean) {
  waitFor("the system authentication control") {authenticationNodes().any(matches)}
  performClick(authenticationNodes().first(matches))
 }
 private fun customKeypad(nodes:List<AccessibilityNodeInfo>):Boolean =
  nodes.any {it.viewIdResourceName=="com.android.systemui:id/key_enter"} &&
   ('0'..'9').all {digit ->nodes.any {it.text?.toString()==digit.toString() && it.isClickable}}
 private fun waitForCredential() {
  waitFor("the system PIN keypad or credential input") {
   val current=authenticationNodes()
   customKeypad(current) || current.any {it.isPassword || it.className?.toString()?.contains("EditText")==true}
  }
 }
 private fun waitForFingerprint() {
  waitFor("the real SystemUI fingerprint prompt") {
   val current=authenticationNodes()
   current.any {it.viewIdResourceName=="com.android.systemui:id/biometric_icon"} && current.any {it.text?.toString()=="Unlock Spunto"}
  }
 }
 private fun configure(enabled:Boolean) {instrumentation.runOnMainSync {app.container.appLock.configure(enabled);if(enabled) app.container.appLock.lock()}}
 private fun fixturePin():String {
  val pin=InstrumentationRegistry.getArguments().getString("spuntoAppLockPin")
  assumeTrue(pin!=null)
  check(pin!!.matches(Regex("[0-9]{4,12}"))) {"Use the synthetic disposable PIN."}
  return pin
 }
 private fun enterPin(pin:String) {
  click("Use screen lock")
  waitForCredential()
  if(customKeypad(authenticationNodes())) {
   pin.forEach {digit ->clickAuthentication {it.text?.toString()==digit.toString() && it.isClickable}}
   clickAuthentication {it.viewIdResourceName=="com.android.systemui:id/key_enter"}
  } else {
   // Older Keyguard confirmation screens expose a focused standard input.
   shell("input text $pin");shell("input keyevent ${KeyEvent.KEYCODE_ENTER}")
  }
  waitFor {app.container.appLock.state.value.unlocked}
 }
 @Test fun launcherAndSelectionStayLockedWithoutCreatingNotesAndWidgetTextStaysHidden() {
  runBlocking {authority.requireCurrent()};val before=runBlocking {app.container.repository.notes.first().size}
  val auth=runBlocking {app.container.auth.config.first()}
  configure(true)
  try {
   ActivityScenario.launch(MainActivity::class.java).use {scenario ->
    waitFor {nodes(instrumentation.uiAutomation.rootInActiveWindow).any {it.text?.toString()=="Spunto is locked"}}
    scenario.recreate()
    waitFor {nodes(instrumentation.uiAutomation.rootInActiveWindow).any {it.text?.toString()=="Spunto is locked"}}
   }
   val selection=Intent(instrumentation.targetContext,CaptureActivity::class.java).setAction(Intent.ACTION_PROCESS_TEXT).putExtra(Intent.EXTRA_PROCESS_TEXT,"Synthetic locked selection").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
   ActivityScenario.launch<CaptureActivity>(selection).use {
    waitFor {nodes(instrumentation.uiAutomation.rootInActiveWindow).any {it.text?.toString()=="Spunto is locked"}}
    assertEquals(before,runBlocking {app.container.repository.notes.first().size})
    assertFalse(nodes(instrumentation.uiAutomation.rootInActiveWindow).any {it.text?.toString()?.contains("Synthetic locked selection")==true})
   }
   CaptureSurfaces.cacheSlip(app,"synthetic-note","Synthetic hidden widget text")
   val prefs=app.getSharedPreferences("device-surfaces",0)
   assertNull(prefs.getString("slip-text",null));assertTrue(CaptureSurfaces.lockedPreviews(app))
   assertEquals(auth,runBlocking {app.container.auth.config.first()})
  } finally {runBlocking {authority.requireCurrent()};configure(false)}
 }
 @Test fun actualSystemCredentialCancellationAndSuccessfulPinUnlock() {
  val pin=fixturePin()
  check(app.getSystemService(KeyguardManager::class.java).isDeviceSecure) {"Enroll the disposable screen lock first."}
  configure(true)
  try {
   ActivityScenario.launch(MainActivity::class.java).use {
    click("Use screen lock")
    waitForCredential()
    shell("input keyevent ${KeyEvent.KEYCODE_BACK}")
    waitFor {nodes(instrumentation.uiAutomation.rootInActiveWindow).any {node ->node.text?.toString()=="Spunto is locked"}}
    assertFalse(app.container.appLock.state.value.unlocked)
    enterPin(pin)
    assertTrue(app.container.appLock.state.value.enabled)
   }
   assertFalse(app.container.appLock.state.value.unlocked)
  } finally {runBlocking {authority.requireCurrent()};configure(false)}
 }
 @Test fun actualStrongFingerprintRejectsUnknownFingerThenUnlocksAndRelocks() {
  assumeTrue(InstrumentationRegistry.getArguments().getString("spuntoAppLockFingerprint")=="yes")
  check(androidx.biometric.BiometricManager.from(app).canAuthenticate(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG)==androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS)
  configure(true)
  try {
   ActivityScenario.launch(MainActivity::class.java).use {scenario ->
    click("Unlock Spunto")
    waitForFingerprint()
    instrumentation.sendStatus(2,Bundle().apply {putString("stream","SPUNTO_FINGERPRINT_READY\n")})
    waitFor {var failed=false;scenario.onActivity {failed=it.lockMessage=="Not recognized. Try again or use your screen lock."};failed && !app.container.appLock.state.value.unlocked}
    instrumentation.sendStatus(2,Bundle().apply {putString("stream","SPUNTO_FINGERPRINT_RETRY_READY\n")})
    waitFor {app.container.appLock.state.value.unlocked}
    shell("input keyevent ${KeyEvent.KEYCODE_HOME}")
    waitFor {!app.container.appLock.state.value.unlocked}
   }
  } finally {runBlocking {authority.requireCurrent()};configure(false)}
 }
 @Test fun unsavedShareDraftSurvivesRelockAndIsAbsentFromLockedAccessibilityWindows() {
  val pin=fixturePin();configure(true)
  val draft="Synthetic immediate lock draft ${System.nanoTime()}"
  val share=Intent(instrumentation.targetContext,CaptureActivity::class.java).setAction(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,"Synthetic shared context").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
  try {
   ActivityScenario.launch<CaptureActivity>(share).use {scenario ->
    enterPin(pin)
    waitFor {nodes(instrumentation.uiAutomation.rootInActiveWindow).any {it.contentDescription?.toString()=="Note text"}}
    val editor=nodes(instrumentation.uiAutomation.rootInActiveWindow).first {it.contentDescription?.toString()=="Note text"}
    assertTrue(editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply {putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,draft)}))
    waitFor {nodes(instrumentation.uiAutomation.rootInActiveWindow).any {it.text?.toString()==draft}}
    instrumentation.runOnMainSync {app.container.appLock.lock()}
    waitFor {nodes(instrumentation.uiAutomation.rootInActiveWindow).any {it.text?.toString()=="Spunto is locked"}}
    val exposed=instrumentation.uiAutomation.windows.flatMap {nodes(it.root)}.mapNotNull {it.text?.toString()}
    assertFalse(exposed.any {it.contains(draft) || it.contains("Synthetic shared context")})
    scenario.onActivity {assertTrue(it.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_SECURE!=0)}
    enterPin(pin)
    waitFor {nodes(instrumentation.uiAutomation.rootInActiveWindow).any {it.text?.toString()==draft}}
   }
  } finally {runBlocking {authority.requireCurrent()};configure(false)}
 }
}
