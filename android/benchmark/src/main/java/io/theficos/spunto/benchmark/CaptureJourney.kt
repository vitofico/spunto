package io.theficos.spunto.benchmark
import android.content.Intent
import android.os.SystemClock
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.rules.ExternalResource

const val PACKAGE = "io.theficos.spunto"
private const val EDITOR = "android.widget.EditText"
private const val IDLE_TIMEOUT_MS = 500L
/** Focused editor/cursor events must not turn every node read into a ten-second idle wait. */
class BoundedUiAutomatorRule : ExternalResource() {
 private var previousIdleTimeout = 10_000L
 override fun before() {
  val config=Configurator.getInstance()
  previousIdleTimeout=config.getWaitForIdleTimeout()
  config.setWaitForIdleTimeout(IDLE_TIMEOUT_MS)
 }
 override fun after() { Configurator.getInstance().setWaitForIdleTimeout(previousIdleTimeout) }
}
/** Compose may expose the description on the editor itself or one of its descendants. */
private fun MacrobenchmarkScope.composer():UiObject2? =
 device.findObject(By.clazz(EDITOR).desc("Note text")) ?: device.findObject(By.clazz(EDITOR).hasDescendant(By.desc("Note text")))
private fun awaitCondition(message:String,timeout:Long=10_000,condition:()->Boolean) {
 val deadline=SystemClock.uptimeMillis()+timeout
 while(SystemClock.uptimeMillis()<deadline) {
  if(runCatching(condition).getOrDefault(false)) return
  SystemClock.sleep(50)
 }
 assertTrue(message,runCatching(condition).getOrDefault(false))
}
/** Call before measureRepeated/profile.collect, never from a measured composer wait. */
fun prepareReturningUserGuide() {
 val instrumentation=InstrumentationRegistry.getInstrumentation()
 val device=UiDevice.getInstance(instrumentation)
 val launcher=Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
  .setClassName(PACKAGE,"$PACKAGE.MainActivity")
  .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
 instrumentation.context.startActivity(launcher)
 var skipped=false
 awaitCondition("Returning-user setup needs an unlocked launcher guide or native composer") {
  val editor=device.findObject(By.pkg(PACKAGE).clazz(EDITOR).desc("Note text"))
   ?: device.findObject(By.pkg(PACKAGE).clazz(EDITOR).hasDescendant(By.desc("Note text")))
  if(editor?.isEnabled==true) true
  else {
   if(!skipped && device.hasObject(By.pkg(PACKAGE).text("Getting started")) && device.hasObject(By.pkg(PACKAGE).desc("Guide progress"))) {
    device.findObject(By.pkg(PACKAGE).text("Skip"))?.takeIf {it.isEnabled}?.let {it.click();skipped=true}
   }
   false
  }
 }
 assertTrue("Guide remained after returning-user preparation",!device.hasObject(By.pkg(PACKAGE).desc("Guide progress")))
 // Backgrounding completes the ordinary activity stop/pending-preference path.
 // No private storage, runtime test flag or guide action enters the measured journey.
 assertTrue("Returning-user setup could not press Home",device.pressHome())
 awaitCondition("Returning-user setup did not leave the app",5000) {device.currentPackageName?.let {it!=PACKAGE}==true}
 device.waitForIdle(IDLE_TIMEOUT_MS)
}
fun MacrobenchmarkScope.awaitComposer() {
 awaitCondition("Native composer did not become ready") { composer()?.isEnabled==true }
}
fun MacrobenchmarkScope.saveSyntheticNote() {
 awaitComposer()
 val original=requireNotNull(composer()).text.orEmpty()
 val unique=System.nanoTime().toString()
 val text="Synthetic benchmark capture $unique #benchmark"
 var searching=false
 try {
  requireNotNull(composer()).text=text
  awaitCondition("Capture text was not entered") { composer()?.text==text }
  val save=device.wait(Until.findObject(By.text("Save")),5000)
  assertNotNull("Save is absent",save)
  save!!.click()
  // Composer clears only after the local transaction succeeds. Pin order and viewport are irrelevant.
  awaitCondition("Local save was not acknowledged") { composer()?.let { it.isEnabled && it.text.isNullOrEmpty() }==true }
  // Validation is outside the spunto.local-save trace metric; no note flags or fixture data are changed.
  val search=device.wait(Until.findObject(By.text("Search")),5000)
  assertNotNull("Search is absent",search)
  search!!.click();searching=true
  assertTrue("Search screen did not open",device.wait(Until.hasObject(By.text("Search notes")),5000)==true)
  assertTrue("Composer did not leave the screen",device.wait(Until.gone(By.desc("Note text")),5000)==true)
  val query=device.wait(Until.findObject(By.clazz(EDITOR).enabled(true)),5000)
  assertNotNull("Search input is absent",query)
  query!!.text=unique
  val result=By.desc(java.util.regex.Pattern.compile("Note,.*"+java.util.regex.Pattern.quote(text)+".*",java.util.regex.Pattern.DOTALL))
  assertNotNull("Acknowledged capture was not found by local search",device.wait(Until.findObject(result),10_000))
 } finally {
  if(searching) {
   val back=device.wait(Until.findObject(By.text("Back")),5000)
   assertNotNull("Cannot return to the timeline",back)
   back!!.click()
  }
  awaitComposer()
  requireNotNull(composer()).text=original
  awaitCondition("Original capture draft was not restored") { composer()?.text.orEmpty()==original }
  device.waitForIdle(IDLE_TIMEOUT_MS)
  // Keep the process alive through the composer's 300 ms draft debounce and Room write.
  SystemClock.sleep(1000)
 }
}
