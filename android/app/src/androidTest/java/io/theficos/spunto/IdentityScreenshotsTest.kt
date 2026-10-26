package io.theficos.spunto

import android.content.res.Configuration
import android.view.ContextThemeWrapper
import android.graphics.Bitmap
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import java.util.Locale
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import android.os.ParcelFileDescriptor
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.espresso.Espresso.pressBack
import androidx.test.platform.app.InstrumentationRegistry
import io.theficos.spunto.core.model.uuidV7
import io.theficos.spunto.data.local.NoteEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import java.util.UUID
import java.io.File
import java.util.concurrent.TimeUnit

/** Opt-in local screenshots of the real timeline with invented notes, restoring the replica afterward. */
class IdentityScreenshotsTest {
 private val compose=createComposeRule()
 private val windowMode=object:ExternalResource() {
  private var original="system"
  override fun before() {
   assumeTrue(InstrumentationRegistry.getArguments().getString("spunto.identity.screenshots")=="SYNTHETIC-DEBUG-ONLY")
   check(context.packageName=="io.theficos.spunto.debug" && shell("getprop ro.kernel.qemu")=="1")
   val container=(context.applicationContext as SpuntoApplication).container
   runBlocking {LocalFixtureAuthority.require(container).requireCurrent();check(container.auth.config.first()==null)}
   original=container.preferences.getString("mode","system").orEmpty()
   instrumentation.runOnMainSync {applyWindowNightMode(context,"system")}
   instrumentation.waitForIdleSync()
  }
  override fun after() {instrumentation.runOnMainSync {applyWindowNightMode(context,original)}}
 }
 @get:Rule val rules=RuleChain.outerRule(Timeout(5,TimeUnit.MINUTES)).around(windowMode).around(compose)
 private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
 private val context get()=instrumentation.targetContext
 private fun shell(command:String)=ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use {it.readText().trim()}

 @Test fun cartaPopulatedTimelineAndBackgroundChoice() {
  assumeTrue(InstrumentationRegistry.getArguments().getString("spunto.identity.screenshots")=="SYNTHETIC-DEBUG-ONLY")
  check(context.packageName=="io.theficos.spunto.debug" && shell("getprop ro.kernel.qemu")=="1")
  val model=SpuntoViewModel(context.applicationContext as SpuntoApplication)
  val authority=runBlocking {LocalFixtureAuthority.require(model.container).also {it.requireCurrent()}}
  check(runBlocking {model.container.auth.config.first()}==null) {"Identity screenshots require a signed-out local replica."}
  val keys=setOf("theme","theme-accent","theme-snapshot","mode","wallpaper","black","background-style")
  val preferences=model.container.preferences
  val original=preferences.all.filterKeys {it in keys}
  val captureDraft=runBlocking {model.repository.dao.draftSnapshot("capture")}
  val outbox=runBlocking {model.repository.dao.outbox()}
  val fixtures=listOf(
   "A thought for later\n\nThe best ideas often arrive in unrelated moments. Keep noticing...\n\n#ideas",
   "Weekend plans\n\n- [ ] Book the coastal train tickets\n- [ ] Look up breakfast spots\n\n#ideas",
  )
  val ids=fixtures.map {uuidV7()}
  val mounted=mutableStateOf(false)
  val dark=mutableStateOf(false)
  val scale=mutableStateOf(1f)
  val narrow=mutableStateOf(false)
  val language=mutableStateOf("en")
  val now=System.currentTimeMillis()
  var failure:Throwable?=null
  try {
   runBlocking {
    authority.requireCurrent()
    // Optional repair names only explicitly authorized fixture IDs; never discover rows by content.
    val cleanupIds=InstrumentationRegistry.getArguments().getString("spunto.identity.cleanupIds").orEmpty().split(',').filter {it.isNotBlank()}
    require(cleanupIds.size<=fixtures.size && cleanupIds.distinct().size==cleanupIds.size)
    cleanupIds.forEach {id ->
     require(UUID.fromString(id).toString()==id.lowercase())
     model.repository.dao.get(id)?.let {row ->
      check(row.body in fixtures && row.title==row.body.substringBefore('\n') && outbox.none {it.noteId==id})
      authority.requireCurrent()
      model.repository.dao.remove(id)
     }
    }
    fixtures.forEachIndexed {index,body ->
     val title=body.substringBefore('\n')
     model.repository.dao.put(NoteEntity(id=ids[index],body=body,title=title,foldedTitle=title.lowercase(),foldedBody=body.lowercase(),createdAt=now-index,updatedAt=now-index))
    }
   }
   check(preferences.edit().putString("theme","carta").remove("theme-accent").remove("theme-snapshot").putString("mode","system").putBoolean("wallpaper",false).putBoolean("black",false).putString("background-style","artwork").commit())
   compose.setContent {
    val density=LocalDensity.current
    val hostContext=LocalContext.current
    // Capture activity owners before a localized Context stops activity discovery.
    val resultOwner=checkNotNull(LocalActivityResultRegistryOwner.current)
    val backOwner=checkNotNull(LocalOnBackPressedDispatcherOwner.current)
    val configuration=Configuration(context.resources.configuration).apply {fontScale=scale.value;setLocale(Locale.forLanguageTag(language.value));if(narrow.value) screenWidthDp=320;uiMode=(uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or if(dark.value) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO}
    val localized=ContextThemeWrapper(hostContext,0).apply {applyOverrideConfiguration(configuration)}
    CompositionLocalProvider(LocalActivityResultRegistryOwner provides resultOwner,LocalOnBackPressedDispatcherOwner provides backOwner,LocalContext provides localized,LocalConfiguration provides configuration,LocalDensity provides Density(density.density,scale.value)) {
     val frame=if(narrow.value) Modifier.width(320.dp).fillMaxHeight() else Modifier.fillMaxSize()
     Box(frame.testTag("identity-canvas")) {if(mounted.value) SpuntoApp(model)}
    }
   }
   compose.runOnIdle {mounted.value=true}
   compose.waitUntil(10_000) {compose.onAllNodesWithContentDescription("A thought for later",substring=true).fetchSemanticsNodes().isNotEmpty()}
   closeSoftKeyboard()
   compose.waitForIdle()
   fun capture(name:String) {
    runBlocking {authority.requireCurrent()}
    closeSoftKeyboard()
    compose.mainClock.advanceTimeBy(500)
    compose.waitForIdle()
    instrumentation.uiAutomation.waitForIdle(150,5000)
    android.os.SystemClock.sleep(500) // SystemUI commits bar appearance outside Compose's virtual clock.
    val bitmap=checkNotNull(instrumentation.uiAutomation.takeScreenshot())
    val directory=File(context.cacheDir,"identity-screenshots").apply {check(mkdirs() || isDirectory)}
    File(directory,name).outputStream().use {check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}
    bitmap.recycle()
   }
   compose.onNodeWithText("spunto.").assertIsDisplayed()
   capture("spunto-carta-artwork.png")
   compose.runOnIdle {dark.value=true}
   capture("spunto-carta-dark-artwork.png")
   compose.runOnIdle {dark.value=false}
   compose.onNodeWithContentDescription(context.getString(R.string.core_settings)).performClick()
   compose.onNode(hasScrollAction()).performScrollToNode(hasText(context.getString(R.string.settings_appearance)) and hasClickAction())
   compose.onNodeWithText(context.getString(R.string.settings_appearance)).performClick()
   compose.onNodeWithText("Artwork").assertIsSelected()
   capture("spunto-carta-appearance.png")
   compose.onNodeWithText("Plain").performClick().assertIsSelected()
   repeat(2) {compose.onNodeWithContentDescription(context.getString(R.string.core_back)).performClick()}
   closeSoftKeyboard()
   compose.waitForIdle()
   assertEquals("plain",preferences.getString("background-style",null))
   capture("spunto-carta-plain.png")
   compose.runOnIdle {scale.value=2f}
   compose.onNodeWithText("spunto.").assertIsDisplayed()
   compose.onNodeWithText(context.getString(R.string.sys_save)).assertIsDisplayed()
   capture("spunto-carta-large.png")
   for(tag in listOf("fr","es")) {
    compose.runOnIdle {scale.value=1.6f;narrow.value=true;language.value=tag}
    val localized=context.createConfigurationContext(Configuration(context.resources.configuration).apply {setLocale(Locale.forLanguageTag(tag))})
    val moreId=compose.onAllNodesWithContentDescription(localized.getString(R.string.core_more_actions)).fetchSemanticsNodes().minBy {it.boundsInRoot.top}.id
    val more=compose.onNode(SemanticsMatcher("topmost localized More") {it.id==moreId})
    more.assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
    val frame=compose.onNodeWithTag("identity-canvas").fetchSemanticsNode().boundsInRoot
    val button=more.fetchSemanticsNode().boundsInRoot
    assertTrue("Localized More must stay inside the narrow home",button.left>=frame.left && button.right<=frame.right)
    capture("spunto-carta-$tag-320-160.png")
    more.performClick()
    compose.onNodeWithText(localized.getString(R.string.core_filter_notes)).assertIsDisplayed()
    pressBack()
    compose.waitForIdle()
   }
   assertEquals(outbox,runBlocking {model.repository.dao.outbox()})
  } catch(error:Throwable) {failure=error} finally {
   fun cleanup(action:()->Unit) {
    try {action()} catch(error:Throwable) {val originalFailure=failure;if(originalFailure==null) failure=error else originalFailure.addSuppressed(error)}
   }
   cleanup {compose.runOnIdle {mounted.value=false};compose.waitForIdle()}
   cleanup {runBlocking {
    authority.requireCurrent()
    ids.forEach {model.repository.dao.remove(it)}
    val current=model.repository.dao.draftSnapshot("capture")
    assertEquals(captureDraft?.body.orEmpty(),current?.body.orEmpty())
    if(captureDraft==null && current!=null && current.body.isEmpty()) model.repository.dao.clearDraftSnapshot(current.id,current.body,current.updatedAt)
   }}
   cleanup {check(preferences.edit().apply {
    keys.forEach {key -> remove(key);when(val value=original[key]) {is String -> putString(key,value);is Boolean -> putBoolean(key,value)}}
   }.commit())}
   cleanup {model.retire()}
  }
  failure?.let {throw it}
 }
}
