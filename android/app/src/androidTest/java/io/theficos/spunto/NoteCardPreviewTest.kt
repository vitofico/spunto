package io.theficos.spunto

import android.os.ParcelFileDescriptor
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.theficos.spunto.core.design.SpuntoTheme
import io.theficos.spunto.core.model.Note
import io.theficos.spunto.core.model.uuidV7
import io.theficos.spunto.data.local.NoteEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real card rendering, with only invented notes and one explicitly owned task row on a debug emulator. */
class NoteCardPreviewTest {
 @get:Rule val timeout=org.junit.rules.Timeout(3,java.util.concurrent.TimeUnit.MINUTES)
 @get:Rule val compose=createComposeRule()

 @Test fun passagesAndLabelsAppearOnceAndCardActionsKeepOriginalTaskSource() {
  val instrumentation=InstrumentationRegistry.getInstrumentation()
  val context=instrumentation.targetContext
  check(context.packageName=="io.theficos.spunto.debug")
  val qemu=ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("getprop ro.kernel.qemu")).bufferedReader().use {it.readText().trim()}
  check(qemu=="1") {"Card preview fixtures are restricted to a debug emulator."}
  val app=context.applicationContext as SpuntoApplication
  val model=SpuntoViewModel(app)
  val authority=runBlocking {LocalFixtureAuthority.require(model.container).also {it.requireCurrent()}}
  check(runBlocking {model.container.auth.config.first()}==null)
  val repository=model.repository
  val id=uuidV7()
  val outbox=runBlocking {repository.dao.outbox()}
  val mounted=mutableStateOf(true)
  val current=mutableStateOf(Note(id,"Synthetic initial note",pinned=true,pending=true))
  val nav=Navigator(mutableStateListOf("timeline")) {}
  val opens=mutableListOf<String>()
  var menus=0
  var inserted=false
  var failure:Throwable?=null
  val openLabel=context.getString(R.string.core_open)
  val openCard=SemanticsMatcher("the real card's open action") {it.config.getOrNull(SemanticsActions.OnClick)?.label==openLabel}
  try {
   compose.setContent {
    val scope=rememberCoroutineScope()
    val feedback=remember {Feedback(SnackbarHostState(),scope)}
    SpuntoTheme("carta","light") {Surface {
     CompositionLocalProvider(LocalNavigator provides nav,LocalFeedback provides feedback) {
      Box(Modifier.width(390.dp)) {if(mounted.value) NoteEntry(model,current.value,{opens+=it},onActions={menus++})}
     }
    }}
   }
   fun show(body:String) {compose.runOnIdle {current.value=Note(id,body,pinned=true,pending=true);nav.home()};compose.waitForIdle()}
   fun once(text:String) {compose.onAllNodesWithText(text,substring=true,useUnmergedTree=true).assertCountEquals(1)}
   fun actions() {
    val previous=opens.size
    compose.onNode(openCard).assertHasClickAction().performSemanticsAction(SemanticsActions.OnClick)
    compose.onNodeWithContentDescription(context.getString(R.string.core_more_actions)).assertIsDisplayed().performClick()
    compose.onNodeWithText("◯").assertIsDisplayed()
    compose.runOnIdle {assertEquals(previous+1,opens.size);assertEquals(id,opens.last());assertEquals(opens.size,menus)}
   }
   val long=List(30) {"A complete synthetic thought"}.joinToString(" ")
   show("#work/plans $long")
   once("A complete synthetic thought")
   compose.onAllNodesWithText("#work/plans",substring=true,useUnmergedTree=true).assertCountEquals(0)
   compose.onNodeWithText("work/plans").assertIsDisplayed().performClick()
   compose.runOnIdle {assertEquals("label:work/plans",nav.route);assertTrue(opens.isEmpty())}
   actions()

   show("**Remember the synthetic milk**")
   once("Remember the synthetic milk");actions()
   show("#work/plans Remember the synthetic milk")
   once("Remember the synthetic milk")
   compose.onAllNodesWithText("#work/plans",substring=true,useUnmergedTree=true).assertCountEquals(0)
   once("work/plans");actions()

   show("#work/plans")
   once("work/plans")
   compose.onAllNodesWithText("#work/plans",substring=true,useUnmergedTree=true).assertCountEquals(0)
   actions()

   show("#ideas - [ ] This is prose\n\n- [ ] This is a real task")
   once("This is prose")
   compose.onAllNodes(isToggleable()).assertCountEquals(1)

   val taskBody="- [ ] Book the synthetic train #travel/rail\r\n#ideas\r\n- [ ] Find the synthetic cafe #ideas"
   runBlocking {
    authority.requireCurrent();check(repository.dao.get(id)==null)
    repository.dao.put(NoteEntity.from(Note(id,taskBody,privateNote=true)))
    inserted=true
   }
   show(taskBody)
   once("Book the synthetic train");once("Find the synthetic cafe")
   compose.onAllNodesWithText("#travel/rail",substring=true,useUnmergedTree=true).assertCountEquals(0)
   compose.onAllNodesWithText("#ideas",substring=true,useUnmergedTree=true).assertCountEquals(0)
   once("ideas");once("travel/rail")
   actions()
   assertEquals(taskBody,runBlocking {repository.dao.get(id)!!.body})
   assertEquals(Note(id,taskBody).title,runBlocking {repository.dao.get(id)!!.title})
   compose.onNode(hasText("Find the synthetic cafe",substring=true) and isToggleable()).assertIsOff().performClick()
   val expected=taskBody.lines().toMutableList().apply {this[2]=this[2].replaceFirst("[ ]","[x]")}.joinToString("\n")
   compose.waitUntil(5_000) {runBlocking {repository.dao.get(id)?.body==expected}}
   val saved=runBlocking {repository.dao.get(id)!!}
   assertEquals("- [ ] Book the synthetic train #travel/rail",saved.body.lines()[0])
   assertEquals("#ideas",saved.body.lines()[1])
   assertEquals("- [x] Find the synthetic cafe #ideas",saved.body.lines()[2])
   compose.runOnIdle {current.value=saved.note()}
   compose.onNode(hasText("Find the synthetic cafe",substring=true) and isToggleable()).assertIsOn()

   show("[#ideas](/n/synthetic-preview-link)")
   once("/n/synthetic-preview-link")
   val linkedText=compose.onNode(hasText("/n/synthetic-preview-link") and SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult),useUnmergedTree=true)
   val layouts=mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
   linkedText.performSemanticsAction(SemanticsActions.GetTextLayoutResult) {get ->assertTrue(get(layouts))}
   val layout=layouts.single()
   val glyph=layout.layoutInput.text.text.indexOf("synthetic-preview-link")
   assertTrue(glyph>=0)
   // The merged parent is also clickable. Tap an actual annotated glyph in the unmerged Text.
   val linkedGlyph=layout.getBoundingBox(glyph).center
   linkedText.performTouchInput {click(linkedGlyph)}
   compose.runOnIdle {assertEquals("synthetic-preview-link",opens.last())}
   show("![#ideas](/a/synthetic-preview-image)")
   once("/a/synthetic-preview-image")
   compose.onNode(openCard).assertHasClickAction().performSemanticsAction(SemanticsActions.OnClick)
   compose.runOnIdle {assertEquals(id,opens.last())}
  } catch(error:Throwable) {failure=error} finally {
   fun cleanup(action:()->Unit) {try {action()} catch(error:Throwable) {val original=failure;if(original==null) failure=error else original.addSuppressed(error)}}
   cleanup {compose.runOnIdle {mounted.value=false};compose.waitForIdle()}
   cleanup {model.retire()}
   cleanup {if(inserted) runBlocking {
    authority.requireCurrent()
    val row=checkNotNull(repository.dao.get(id))
    check(row.body.lines().first()=="- [ ] Book the synthetic train #travel/rail")
    check(row.body.lines().getOrNull(1)=="#ideas")
    check(row.body.lines().getOrNull(2) in listOf("- [ ] Find the synthetic cafe #ideas","- [x] Find the synthetic cafe #ideas"))
    repository.dao.outbox().filter {it.noteId==id}.forEach {repository.dao.removeOutbox(it.opId)}
    repository.dao.clearLabels(id);repository.dao.remove(id)
   }}
   cleanup {assertEquals(outbox,runBlocking {repository.dao.outbox()})}
  }
  failure?.let {throw it}
 }
}
