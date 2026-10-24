package io.theficos.spunto

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.ParcelFileDescriptor
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.io.File

/** Original fictional notes, captured only on the named pristine, signed-out screenshot AVD. */
@RunWith(AndroidJUnit4::class)
class DistributionScreenshotsTest {
 private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
 private val context get()=instrumentation.targetContext
 private val container get()=(context.applicationContext as SpuntoApplication).container
 private val compose=createAndroidComposeRule<MainActivity>()
 private lateinit var authority:LocalFixtureAuthority
 private val notes=listOf(
  "Death Star inspection\n\n- [ ] Inspect every exhaust vent\n- [x] Label the red buttons\n- [ ] Add railings near dramatic drops\n\n#imperial/operations #projects/death-star",
  "Luke: a quieter follow-up\n\n**Next step:** send one calm message. Leave the fleet parked.\n\n- [ ] Suggest a walk\n- [ ] Practise listening\n\n#imperial/family #people/luke",
  "Helmet maintenance\n\n- [ ] Polish the lenses\n- [ ] Replace the left speaker filter\n- [x] Lower breathing volume for calls\n\n#life/gear/helmet",
  "Five quiet minutes\n\nUnclench the gloves. Let the cape settle.\n\n- [ ] Silence bridge notifications\n- [ ] Count ten slow breaths\n\n#life/meditation #habits/daily",
  "Lunch away from the bridge\n\n- [ ] Book a table with helmet clearance\n- [ ] Try soup after the meeting\n\nA calendar needs a small rebellion.\n\n#life/food #habits/breaks",
 )
 private fun shell(command:String)=ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use {it.readText().trim()}
 private val screenshotGuard=object:ExternalResource() {
  override fun before() {
   assumeTrue(InstrumentationRegistry.getArguments().getString("spunto.distribution.screenshots")=="SYNTHETIC-DEBUG-ONLY")
   check(context.packageName=="io.theficos.spunto.debug")
   check(shell("getprop ro.kernel.qemu")=="1" && shell("getprop ro.boot.qemu.avd_name")=="SpuntoVaderScreenshots") {"Store captures require the dedicated disposable screenshot AVD."}
   runBlocking {
    authority=LocalFixtureAuthority.require(container)
    authority.requireCurrent()
    check(container.auth.config.first()==null)
    check(container.repository.dao.all().isEmpty()) {"Store captures require a fresh debug replica; never erase retained data."}
    check(container.repository.dao.outbox().isEmpty())
    notes.asReversed().forEach {container.repository.create(it)}
    check(container.repository.search("#imperial").size==2)
   }
   check(container.preferences.edit().putString("theme","ardesia").putString("mode","dark").commit())
  }
 }
 // The target/replica guard and theme selection must run before MainActivity composes.
 @get:Rule val rules=RuleChain.outerRule(org.junit.rules.Timeout(5,java.util.concurrent.TimeUnit.MINUTES)).around(screenshotGuard).around(GuidePreferenceFixture(true)).around(compose)
 @Test fun captureRealScreensWithSyntheticNotes() {
  val directory=File(context.cacheDir,"distribution-screenshots").apply {check(mkdirs() || isDirectory)}
  fun keyboardShown():Boolean? {
   val states=Regex("\\bmInputShown=(true|false)\\b").findAll(shell("dumpsys input_method")).map {it.groupValues[1]}.distinct().toList()
   return when(states) {listOf("true") -> true;listOf("false") -> false;else -> null}
  }
  fun hideKeyboard(awaitVisible:Boolean=false) {
   compose.waitForIdle()
   if(awaitVisible) compose.waitUntil(10_000) {keyboardShown()==true}
   val shown=keyboardShown()
   check(shown!=null) {"The screenshot keyboard state could not be verified."}
   if(shown) {
    shell("input keyevent 4")
    compose.waitUntil(10_000) {keyboardShown()==false}
   }
   compose.waitForIdle()
   instrumentation.uiAutomation.waitForIdle(150,5000)
   check(keyboardShown()==false) {"The screenshot keyboard must be hidden."}
  }
  fun waitForDisplayed(matcher:SemanticsMatcher) {
   compose.waitUntil(10_000) {compose.onAllNodes(matcher).fetchSemanticsNodes().size==1 && compose.onNode(matcher).isDisplayed()}
  }
  fun capture(name:String) {
   runBlocking {authority.requireCurrent();check(container.auth.config.first()==null)}
   hideKeyboard()
   compose.mainClock.advanceTimeBy(500)
   compose.waitForIdle()
   instrumentation.uiAutomation.waitForIdle(150,5000)
   check(instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()==context.packageName) {"Only the Spunto interface may appear in store captures."}
   val bitmap=checkNotNull(instrumentation.uiAutomation.takeScreenshot())
   File(directory,name).outputStream().use {check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}
   bitmap.recycle()
  }
  compose.waitUntil(10_000) {compose.onAllNodesWithContentDescription("Death Star inspection",substring=true).fetchSemanticsNodes().isNotEmpty()}
  hideKeyboard(awaitVisible=true)
  compose.onNode(hasScrollAction() and hasAnyDescendant(hasContentDescription("Note,",substring=true))).performScrollToNode(hasContentDescription("Death Star inspection",substring=true))
  compose.onNodeWithContentDescription("Settings").assertIsDisplayed()
  capture("1.png")
  compose.onNodeWithContentDescription("Search").performClick()
  val search=hasSetTextAction() and hasText("Search notes")
  compose.onNode(search).performClick()
  compose.waitUntil(10_000) {compose.onAllNodes(search and isFocused()).fetchSemanticsNodes().size==1}
  compose.onNode(search and isFocused()).performTextInput("#imperial")
  val result={title:String ->hasClickAction() and hasText(title,substring=true)}
  compose.waitUntil(10_000) {listOf("Death Star inspection","Luke: a quieter follow-up").all {compose.onAllNodes(result(it)).fetchSemanticsNodes().size==1}}
  hideKeyboard(awaitVisible=true)
  capture("2.png")
  compose.onNode(result("Death Star inspection")).performClick()
  waitForDisplayed(hasContentDescription("More note actions"))
  waitForDisplayed(editableNote)
  compose.onNodeWithContentDescription("More note actions").assertIsDisplayed()
  capture("3.png")
  compose.onNodeWithContentDescription("Back",useUnmergedTree=true).performClick()
  waitForDisplayed(hasSetTextAction() and hasText("#imperial"))
  // Returning from a note restores the spent focus flag; the keyboard may already be hidden.
  hideKeyboard()
  compose.onNodeWithContentDescription("Back",useUnmergedTree=true).performClick()
  waitForDisplayed(hasContentDescription("Choose labels and views"))
  hideKeyboard()
  compose.onNodeWithContentDescription("Choose labels and views").performClick()
  waitForDisplayed(hasSetTextAction() and hasText("Find a label"))
  waitForDisplayed(hasContentDescription("Drag handle"))
  val sheetDragDistance=compose.activity.window.decorView.height*0.45f
  compose.onNodeWithContentDescription("Drag handle").performTouchInput {
   swipe(start=center,end=Offset(center.x,center.y-sheetDragDistance),durationMillis=500)
  }
  compose.waitForIdle()
  compose.onNode(hasScrollAction() and hasAnyAncestor(isDialog())).performScrollToNode(hasTestTag("label:imperial"))
  waitForDisplayed(hasTestTag("label:imperial"))
  compose.onNodeWithTag("label:imperial").assertIsDisplayed()
  capture("4.png")
  // Render the same shipped adaptive icon through Android's drawable implementation.
  val drawable=context.getDrawable(R.mipmap.ic_launcher)!!
  val icon=Bitmap.createBitmap(512,512,Bitmap.Config.ARGB_8888)
  drawable.setBounds(0,0,512,512);drawable.draw(Canvas(icon))
  File(directory,"icon.png").outputStream().use {check(icon.compress(Bitmap.CompressFormat.PNG,100,it))}
  icon.recycle()
  runBlocking {authority.requireCurrent();check(container.auth.config.first()==null)}
 }
}
