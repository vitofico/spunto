package io.theficos.spunto

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
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
