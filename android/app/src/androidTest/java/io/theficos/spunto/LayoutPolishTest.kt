package io.theficos.spunto

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import android.content.res.Configuration
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Locale
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.theficos.spunto.core.design.SpuntoTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Pure layout fixtures: no account, local replica, microphone or network access. */
class LayoutPolishTest {
 @get:Rule val compose=createComposeRule()

 @Composable private fun CompactLargeText(content:@Composable ()->Unit) {
  val density=LocalDensity.current
  CompositionLocalProvider(LocalDensity provides Density(density.density,2f)) {
   SpuntoTheme("carta","light") {Surface(Modifier.width(320.dp).height(480.dp)) {Box(Modifier.fillMaxSize()) {content()}}}
  }
 }

 @Test fun longNotesFoldAfterEightLinesAndShowMoreUnfoldsInPlace() {
  val long=(1..40).joinToString("\n\n") { "Synthetic paragraph $it" }+"\n\nSynthetic tail line"
  compose.setContent {SpuntoTheme("carta","light") {Column {FoldedBody(long) {io.theficos.spunto.core.design.Markdown(it)};FoldedBody("Synthetic short note") {io.theficos.spunto.core.design.Markdown(it)}}}}
  compose.onAllNodesWithText("Show more").assertCountEquals(1)
  compose.onNodeWithText("Synthetic paragraph 1").assertIsDisplayed();compose.onNodeWithText("Synthetic paragraph 12").assertIsNotDisplayed()
  compose.onNodeWithText("Show more").assertHasClickAction().performClick()
  compose.onNodeWithText("Synthetic tail line").assertExists();compose.onNodeWithText("Show less").assertExists()
 }
 @Test fun saveStaysVisibleBesideScrollingToolsAtTwoHundredPercent() {
  var saves=0
  val saving=mutableStateOf(false)
  compose.setContent {CompactLargeText {
   ComposerToolbar(tools={
    TextButton(onClick={}) {Text("# Label")}
    ComposerDictationAction(true,false,false) {}
    TextButton(onClick={}) {Text("Record voice memo")}
   },save={ComposerSaveAction(!saving.value,saving.value,false) {saves++;saving.value=true}})
  }}
  val save=compose.onNodeWithText("Save")
  save.assertIsDisplayed().assertIsEnabled()
  val before=save.fetchSemanticsNode().boundsInRoot
  compose.onNode(hasScrollAction()).performTouchInput {swipeLeft()}
  save.assertIsDisplayed()
  assertEquals(before,save.fetchSemanticsNode().boundsInRoot)
  save.performClick()
  compose.onNodeWithText("Saving…").assertIsDisplayed().assertIsNotEnabled()
  compose.runOnIdle {assertEquals(1,saves)}
 }

 @OptIn(ExperimentalMaterial3Api::class)
 @Test fun localizedDoneAndSavingStayInsideTheAppBarAtTwoHundredPercent() {
  val base=InstrumentationRegistry.getInstrumentation().targetContext
  val language=mutableStateOf("en");val saving=mutableStateOf(false);var completions=0
  compose.setContent {
   val configuration=Configuration(base.resources.configuration).apply {setLocale(Locale.forLanguageTag(language.value))}
   val context=base.createConfigurationContext(configuration)
   CompositionLocalProvider(LocalContext provides context,LocalConfiguration provides configuration) {
    CompactLargeText {
     TopAppBar(modifier=Modifier.testTag("compact-note-bar"),title={Text("Note",maxLines=1)},windowInsets=WindowInsets(0,0,0,0),
      navigationIcon={IconButton(onClick={}) {Text("‹")}},actions={
       IconButton(onClick={}) {Text("•")}
       NoteDoneAction(saving.value,!saving.value) {completions++;saving.value=true}
      })
    }
   }
  }
  for(tag in listOf("en","es","it","fr","de")) {
   compose.runOnIdle {language.value=tag;saving.value=false}
   val configuration=Configuration(base.resources.configuration).apply {setLocale(Locale.forLanguageTag(tag))}
   val context=base.createConfigurationContext(configuration)
   val done=compose.onNodeWithText(context.getString(R.string.core_done))
   done.assertIsDisplayed().assertIsEnabled().performClick()
   val progress=compose.onNodeWithText(context.getString(R.string.core_saving))
   progress.assertIsDisplayed().assertIsNotEnabled()
   val frame=compose.onNodeWithTag("compact-note-bar").fetchSemanticsNode().boundsInRoot
   val label=progress.fetchSemanticsNode().boundsInRoot
   assertTrue("Saving label must remain inside the 320dp app bar for $tag",label.left>=frame.left && label.right<=frame.right && label.top>=frame.top && label.bottom<=frame.bottom)
  }
  compose.runOnIdle {assertEquals(5,completions)}
 }

 @Test fun longSyncStatusKeepsItsActionVisibleAndSeparate() {
  var syncs=0
  compose.setContent {CompactLargeText {SyncStatusNotice(Int.MAX_VALUE,true) {syncs++}}}
  val status=compose.onNodeWithText("2147483647 notes waiting to sync · Server unreachable")
  val sync=compose.onNodeWithText("Sync")
  status.assertIsDisplayed();sync.assertIsDisplayed().assertIsEnabled()
  assertTrue(status.fetchSemanticsNode().boundsInRoot.right<=sync.fetchSemanticsNode().boundsInRoot.left)
  sync.performClick()
  compose.runOnIdle {assertEquals(1,syncs)}
 }

 @Test fun plainHttpWarningCanScrollToBothActionsWithLargeText() {
  var connects=0;var backs=0
  compose.setContent {CompactLargeText {
   PlainHttpWarning("http://synthetic-local-fixture.invalid:8000",{connects++},{backs++})
  }}
  compose.onNodeWithText("Connect on this network").performScrollTo().assertIsDisplayed().performClick()
  compose.onNodeWithText("Go back").performScrollTo().assertIsDisplayed().performClick()
  compose.runOnIdle {assertEquals(1,connects);assertEquals(1,backs)}
 }

 @Test fun qrSheetShowsItsPreviewAndKeepsCancelReachableWithLargeText() {
  val open=mutableStateOf(true)
  compose.setContent {CompactLargeText {
   if(open.value) PairingQrSheet(onDismiss={open.value=false}) {
    Box(Modifier.fillMaxWidth().height(300.dp)) {Text("Synthetic camera preview")}
   }
  }}
  compose.onNodeWithText("Scan pairing QR").assertIsDisplayed()
  compose.onNodeWithText("Synthetic camera preview").assertIsDisplayed()
  compose.onNodeWithText("Cancel").assertIsDisplayed().performClick()
  compose.onNodeWithText("Scan pairing QR").assertDoesNotExist()
 }
}
