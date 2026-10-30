package io.theficos.spunto

import android.net.Uri
import android.os.Looper
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.test.platform.app.InstrumentationRegistry
import io.theficos.spunto.core.design.SpuntoTheme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Exercises the real picker/composer path; completion disposes it like quick-capture Activity.finish(). */
@OptIn(ExperimentalTestApi::class,ExperimentalCoroutinesApi::class)
class CaptureImageSaveTest {
 @get:Rule val timeout=org.junit.rules.Timeout(3,java.util.concurrent.TimeUnit.MINUTES)
 // Queue test continuations so Room resumes cannot run a test frame on an IO worker.
 @get:Rule val compose=createComposeRule(effectContext=StandardTestDispatcher())
 @Test fun imageOnlyCaptureClosesAfterItsImageAndNoteAreDurable()=capture("",1)
 @Test fun textAndImagesCloseOnlyAfterTheWholeCaptureCommits()=capture("Synthetic quick capture",2)
 private fun capture(body:String,count:Int) {
  val context=InstrumentationRegistry.getInstrumentation().targetContext
  check(context.packageName=="io.theficos.spunto.debug")
  val model=SpuntoViewModel(context.applicationContext as SpuntoApplication)
  val authority=runBlocking {LocalFixtureAuthority.require(model.container)}
  val key="test-image-capture:${System.nanoTime()}"
  val source=File.createTempFile("synthetic-picker-",".png",context.cacheDir).apply {writeBytes(byteArrayOf(1,2,3))}
  val folder=model.container.privateDirectory("pending-attachments")
  val before=folder.listFiles().orEmpty().toSet()
  val mounted=mutableStateOf(true)
  var completed:String?=null
  var completionFailure:Throwable?=null
  val registry=object:ActivityResultRegistry() {
   override fun <I,O> onLaunch(requestCode:Int,contract:ActivityResultContract<I,O>,input:I,options:ActivityOptionsCompat?) {
    check(contract is ActivityResultContracts.PickVisualMedia) {"Capture image fixture received an unexpected activity contract."}
    dispatchResult(requestCode,Uri.fromFile(source))
   }
  }
  val owner=object:ActivityResultRegistryOwner {override val activityResultRegistry=registry}
  try {
   compose.setContent {SpuntoTheme {CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {Column {
    SideEffect {assertSame("Capture composition must apply on the Android main thread.",Looper.getMainLooper(),Looper.myLooper())}
    if(mounted.value) Composer(model,initial=body,draftId=key,autoFocus=false,onPlainCaptureSaved={id ->
     // Capture completion has the same teardown effect as the quick-capture activity finishing.
     try {
      assertSame("Capture completion must run on the Android main thread.",Looper.getMainLooper(),Looper.myLooper())
      runBlocking {
      authority.requireCurrent()
      val note=model.repository.dao.get(id)!!
      val images=model.repository.dao.attachments(id).first()
      assertEquals(count,images.size);assertTrue(images.all {it.marker in note.body && File(it.localPath).isFile})
      assertTrue(note.body.startsWith(body));assertNull(model.repository.dao.draftSnapshot(key))
     }} catch(error:Throwable) {completionFailure=error}
     completed=id;mounted.value=false
    },onSave={error("Plain capture bypassed durable save")})
   }}}}
   val add=context.getString(R.string.core_add_image)
   val save=context.getString(R.string.sys_save)
   val text=context.getString(R.string.core_note_text)
   compose.waitUntil(10_000) {compose.onAllNodes(hasContentDescription(text) and isEnabled()).fetchSemanticsNodes().isNotEmpty()}
   // An empty unfocused composer keeps its toolbar collapsed; opening capture starts by focusing it.
   compose.onNodeWithContentDescription(text).assertTextEquals(body).performSemanticsAction(SemanticsActions.OnClick)
   compose.waitUntil(10_000) {compose.onAllNodes(hasContentDescription(add) and isEnabled()).fetchSemanticsNodes().isNotEmpty()}
   compose.onNodeWithContentDescription(text).assertTextEquals(body)
   // Exercise the matched accessible actions without depending on keyboard animation coordinates.
   repeat(count) {index ->
    compose.onNodeWithContentDescription(add).performSemanticsAction(SemanticsActions.OnClick)
    val attached=context.resources.getQuantityString(R.plurals.core_images_to_attach,index+1,index+1)
    // Compose can be idle while the picker copy is still running on Dispatchers.IO.
    compose.waitUntil(10_000) {compose.onAllNodesWithText(attached).fetchSemanticsNodes().size==1}
   }
   compose.onNodeWithText(context.resources.getQuantityString(R.plurals.core_images_to_attach,count,count)).assertExists()
   runBlocking {authority.requireCurrent()}
   compose.onNodeWithText(save).assertIsEnabled().performSemanticsAction(SemanticsActions.OnClick)
   compose.waitUntil(10_000) {completed!=null}
   completionFailure?.let {throw it}
   compose.waitForIdle()
   runBlocking {
    assertEquals(count,model.repository.dao.attachments(completed!!).first().size)
    assertNull(model.repository.dao.draftSnapshot(key))
   }
  } finally {
   val lease=model.repository.beginEditor(null,key)
   compose.runOnIdle {mounted.value=false};compose.waitForIdle()
   runBlocking {
    authority.requireCurrent()
    completed?.let {id ->
     model.repository.dao.attachments(id).first().forEach {model.repository.dao.removeAttachment(it.id)}
     model.repository.dao.outbox().filter {it.noteId==id}.forEach {model.repository.dao.removeOutbox(it.opId)}
     model.repository.dao.clearLabels(id);model.repository.dao.remove(id)
    }
    model.repository.saveDraft(key,"");model.repository.clearDraft(key,"")
   }
   model.repository.abandonEditor(lease);model.retire();source.delete()
   folder.listFiles().orEmpty().filter {it !in before}.forEach {it.delete()}
  }
 }
}
