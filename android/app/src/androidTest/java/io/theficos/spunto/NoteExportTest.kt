package io.theficos.spunto

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.theficos.spunto.core.design.SpuntoTheme
import io.theficos.spunto.core.model.Note
import io.theficos.spunto.data.local.NoteEntity
import io.theficos.spunto.data.sync.AuthConfig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

/** Only task-created rows and synthetic outbound payloads; never a real receiving app or owner data. */
@OptIn(ExperimentalTestApi::class,ExperimentalCoroutinesApi::class)
class NoteExportTest {
 // Queue test continuations; the default unconfined dispatcher can resume frames on an IO worker.
 @get:Rule val compose=createComposeRule(effectContext=StandardTestDispatcher())
 @get:Rule val timeout=org.junit.rules.Timeout(5,java.util.concurrent.TimeUnit.MINUTES)
 private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
 private val app get()=instrumentation.targetContext.applicationContext as SpuntoApplication
 private var signedIn:AuthConfig?=null
 @Composable private fun ExportContent(model:SpuntoViewModel,note:Note,enabled:Boolean) {
  SpuntoTheme {
   SideEffect {assertSame("Export composition must apply on the Android main thread.",Looper.getMainLooper(),Looper.myLooper())}
   NoteExportActions(model,note,enabled)
  }
 }
 /** The fixtures need a signed-out app: each test signs this emulator out, then signs the same device back in. */
 @Before fun signOut() {
  check(instrumentation.targetContext.packageName=="io.theficos.spunto.debug")
  check(android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("getprop ro.kernel.qemu")).bufferedReader().use {it.readText()}.trim()=="1") {"Export fixtures require an emulator."}
  runBlocking {val auth=app.container.current.auth;signedIn=auth.config.first();auth.clear()}
 }
 @After fun signBackIn() {runBlocking {signedIn?.let {app.container.current.auth.save(it)}}}
 private fun fixture():Triple<SpuntoViewModel,LocalFixtureAuthority,Note> {
  val model=SpuntoViewModel(app)
  val authority=runBlocking {LocalFixtureAuthority.require(model.container)}
  check(!model.container.appLock.state.value.enabled)
  runBlocking {check(model.container.auth.config.first()==null);authority.requireCurrent()}
  val note=Note("export-test-${System.nanoTime()}","# Synthetic città\n\nOriginal **Markdown** #export",privateNote=true)
  runBlocking {authority.requireCurrent();model.repository.dao.put(NoteEntity.from(note))}
  return Triple(model,authority,note)
 }
 private fun cleanup(model:SpuntoViewModel,authority:LocalFixtureAuthority,note:Note) {
  runBlocking {authority.requireCurrent();model.repository.dao.draftSnapshot(note.id)?.let {model.repository.dao.clearDraftSnapshot(it.id,it.body,it.updatedAt)};model.repository.dao.remove(note.id)}
 }
 @Test fun explicitPrivateShareUsesOnlySavedTextAndNoCredentialsOrAttachmentGrants() {
  val (model,authority,note)=fixture()
  val captured=AtomicReference<Intent?>()
  val monitor=object:Instrumentation.ActivityMonitor() {
   override fun onStartActivity(intent:Intent):Instrumentation.ActivityResult? {
    if(intent.action!=Intent.ACTION_CHOOSER) return null
    captured.set(intent);return Instrumentation.ActivityResult(Activity.RESULT_CANCELED,null)
   }
  }
  instrumentation.addMonitor(monitor)
  val enabled=mutableStateOf(false)
  try {
   compose.setContent {ExportContent(model,note,enabled.value)}
   compose.onNodeWithText("Share").assertIsNotEnabled()
   compose.runOnIdle {enabled.value=true}
   compose.onNodeWithText("Share").performClick()
   compose.waitUntil(10000) {compose.onAllNodesWithText("Share private note?").fetchSemanticsNodes().isNotEmpty()}
   assertNull(captured.get())
   compose.onNodeWithText("Cancel").performClick();assertNull(captured.get())
   compose.onNodeWithText("Share").performClick()
   compose.waitUntil(10000) {compose.onAllNodesWithText("Continue").fetchSemanticsNodes().isNotEmpty()}
   compose.onNodeWithText("Continue").performClick()
   compose.waitUntil(10000) {captured.get()!=null}
   @Suppress("DEPRECATION") val outbound=captured.get()!!.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
   assertEquals(Intent.ACTION_SEND,outbound.action);assertEquals("text/plain",outbound.type)
   assertEquals(note.body,outbound.getStringExtra(Intent.EXTRA_TEXT));assertEquals(note.title,outbound.getStringExtra(Intent.EXTRA_SUBJECT))
   assertEquals(setOf(Intent.EXTRA_TEXT,Intent.EXTRA_SUBJECT),outbound.extras!!.keySet())
   assertNull(outbound.data);assertNull(outbound.clipData);assertEquals(0,outbound.flags)
   runBlocking {authority.requireCurrent();assertEquals(note.body,model.repository.dao.get(note.id)!!.body);assertNull(model.repository.dao.draftSnapshot(note.id))}
  } finally {instrumentation.removeMonitor(monitor);cleanup(model,authority,note)}
 }
 @Test fun documentPickerUsesMarkdownAndChangedDraftInvalidatesItsResultWithoutWriting() {
  val (model,authority,note)=fixture()
  val picker=AtomicReference<Intent?>()
  val monitor=object:Instrumentation.ActivityMonitor() {
   override fun onStartActivity(intent:Intent):Instrumentation.ActivityResult? {
    if(intent.action!=Intent.ACTION_CREATE_DOCUMENT) return null
    picker.set(intent)
    return Instrumentation.ActivityResult(Activity.RESULT_OK,Intent().setData(Uri.parse("content://synthetic.invalid/never-written")))
   }
  }
  instrumentation.addMonitor(monitor)
  try {
   compose.setContent {ExportContent(model,note,true)}
   compose.onNodeWithText("Save Markdown").performClick()
   compose.waitUntil(10000) {compose.onAllNodesWithText("Continue").fetchSemanticsNodes().isNotEmpty()}
   compose.onNodeWithText("Continue").performClick()
   compose.waitUntil(10000) {compose.onAllNodesWithText("Save selected Markdown?").fetchSemanticsNodes().isNotEmpty()}
   assertEquals("text/markdown",picker.get()!!.type);assertTrue(picker.get()!!.hasCategory(Intent.CATEGORY_OPENABLE))
   assertEquals(markdownFileName(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext,note.title),picker.get()!!.getStringExtra(Intent.EXTRA_TITLE))
   assertNull(picker.get()!!.data)
   runBlocking {authority.requireCurrent();model.repository.saveDraft(note.id,"A separate unsaved synthetic edit")}
   compose.waitUntil(10000) {compose.onAllNodesWithText("Save selected Markdown?").fetchSemanticsNodes().isEmpty()}
   assertFalse(model.message.value.contains("Markdown saved"))
   runBlocking {assertNull(readNoteForExport(model,note.id));assertEquals(note.body,model.repository.dao.get(note.id)!!.body);assertEquals("A separate unsaved synthetic edit",model.repository.dao.draftSnapshot(note.id)!!.body)}
  } finally {instrumentation.removeMonitor(monitor);cleanup(model,authority,note)}
 }
 @Test fun lockedExportReadRefusesAndLeavesTheSavedNoteAndDraftUntouched() {
  val (model,authority,note)=fixture()
  try {
   runBlocking {assertNotNull(readNoteForExport(model,note.id))}
   instrumentation.runOnMainSync {model.container.appLock.configure(true);model.container.appLock.lock()}
   runBlocking {assertNull(readNoteForExport(model,note.id));authority.requireCurrent();assertEquals(note.body,model.repository.dao.get(note.id)!!.body);assertNull(model.repository.dao.draftSnapshot(note.id))}
  } finally {instrumentation.runOnMainSync {model.container.appLock.configure(false)};cleanup(model,authority,note)}
 }
 @androidx.test.filters.SdkSuppress(minSdkVersion=29)
 @Test fun selectedMarkdownDocumentGetsExactUtf8AndOnlyOwnedFixtureIsRemoved() {
  org.junit.Assume.assumeTrue(android.os.Build.VERSION.SDK_INT>=29)
  val (model,authority,note)=fixture()
  val resolver=instrumentation.targetContext.contentResolver
  val name="spunto-export-test-${System.nanoTime()}.md"
  val values=android.content.ContentValues().apply {
   put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME,name)
   put(android.provider.MediaStore.MediaColumns.MIME_TYPE,"text/markdown")
   put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,android.os.Environment.DIRECTORY_DOWNLOADS)
   put(android.provider.MediaStore.MediaColumns.IS_PENDING,1)
  }
  val uri=checkNotNull(resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,values))
  val monitor=object:Instrumentation.ActivityMonitor() {
   override fun onStartActivity(intent:Intent):Instrumentation.ActivityResult? {
    if(intent.action!=Intent.ACTION_CREATE_DOCUMENT) return null
    return Instrumentation.ActivityResult(Activity.RESULT_OK,Intent().setData(uri))
   }
  }
  instrumentation.addMonitor(monitor)
  try {
   compose.setContent {ExportContent(model,note,true)}
   compose.onNodeWithText("Save Markdown").performClick()
   compose.waitUntil(10000) {compose.onAllNodesWithText("Continue").fetchSemanticsNodes().isNotEmpty()}
   compose.onNodeWithText("Continue").performClick()
   compose.waitUntil(10000) {compose.onAllNodesWithText("Save selected Markdown?").fetchSemanticsNodes().isNotEmpty()}
   compose.onNodeWithText("Save selected Markdown",substring=false).performClick()
   compose.waitUntil(10000) {model.message.value.startsWith("Markdown saved.")}
   val actual=checkNotNull(resolver.openInputStream(uri)).use {it.readBytes()}
   assertArrayEquals(note.body.toByteArray(Charsets.UTF_8),actual)
   runBlocking {authority.requireCurrent();assertEquals(note.body,model.repository.dao.get(note.id)!!.body);assertNull(model.repository.dao.draftSnapshot(note.id))}
   instrumentation.sendStatus(2,android.os.Bundle().apply {putString("spunto_export_readback_sha256",exportDigest(String(actual,Charsets.UTF_8)));putString("spunto_export_fixture_uri",uri.toString())})
  } finally {
   instrumentation.removeMonitor(monitor)
   val removed=resolver.delete(uri,null,null)
   val absent=checkNotNull(resolver.query(uri,arrayOf(android.provider.MediaStore.MediaColumns._ID),null,null,null)).use {it.count==0}
   instrumentation.sendStatus(2,android.os.Bundle().apply {putInt("spunto_export_fixture_removed_rows",removed);putBoolean("spunto_export_fixture_absent",absent)})
   cleanup(model,authority,note)
   assertEquals(1,removed);assertTrue(absent)
  }
 }

}
