package io.theficos.spunto

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import io.theficos.spunto.core.design.SpuntoTheme
import io.theficos.spunto.core.model.PluginPublicationFence
import io.theficos.spunto.data.sync.AuthConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class NativePluginControlsTest {
 @get:Rule val compose=createComposeRule()
 @get:Rule val timeout=org.junit.rules.Timeout(10,java.util.concurrent.TimeUnit.MINUTES)
 private fun row(id:String="voice-memos",state:String="waiting",pin:Int=0)=Json.parseToJsonElement("""{"id":"$id","name":"$id","description":"Synthetic plugin","kind":"server","category":"capture","api":"1.0","version":"1.0.0","switch":"${if(state=="removed") "off" else "on"}","state":"$state","pinned_by_env":$pin,"permissions":["ai.transcribe"],"granted":["ai.transcribe"],"can_enable":${state!="removed"},"can_delete_data":true,"data_count":214}""").jsonObject
 private fun catalog(row:JsonObject,revision:Int)=buildJsonObject {put("plugins_rev",revision);put("plugins",JsonArray(listOf(row)))}
 private fun manifest(revision:Int)=buildJsonObject {put("plugins_rev",revision);put("plugins",JsonArray(emptyList()))}

 @Test fun wantedWaitingAndFailedRemainCheckedWhilePinnedAndRemovedHaveNoToggle() {
  var toggles=0
  compose.setContent {SpuntoTheme {Column {
   NativePluginRow(NativePluginFacts(row()),true,false,{toggles++},{},{},{})
   NativePluginRow(NativePluginFacts(row("failed-plugin","failed")),true,false,{toggles++},{},{},{})
   NativePluginRow(NativePluginFacts(row("pinned-plugin",pin=1)),true,false,{toggles++},{},{},{})
   NativePluginRow(NativePluginFacts(row("removed-plugin","removed",1)),true,false,{toggles++},{},{},{})
  }}}
  compose.onNodeWithContentDescription("voice-memos wanted on").assertIsOn()
  compose.onNodeWithContentDescription("failed-plugin wanted on").assertIsOn()
  compose.onNodeWithContentDescription("pinned-plugin wanted on").assertDoesNotExist()
  compose.onNodeWithContentDescription("removed-plugin wanted on").assertDoesNotExist()
  compose.onNodeWithText("Removed").assertExists();compose.onNodeWithText("214 records kept").assertExists()
  compose.runOnIdle {assertEquals(0,toggles)}
 }
 @Test fun offlineSettingsShowValuesAndEnvironmentStatusWithoutInputsOrRequests() {
  var links=0
  val page=safePluginPage(Json.parseToJsonElement("""{"schema":{"type":"object","properties":{"bot_token":{"type":"string","title":"Bot token","x-secret":true},"language":{"type":"string","title":"Language"}}},"values":{"bot_token":"synthetic-private-placeholder","language":"Italian"},"secrets":{"bot_token":{"set":false}}}""").jsonObject)
  compose.setContent {SpuntoTheme {Column {NativePluginSettingsValues(NativePluginFacts(row("telegram")),page,false) {links++}}}}
  compose.onNodeWithText("Language · Italian").assertIsDisplayed();compose.onNodeWithText("Bot token · Not set in the environment").assertIsDisplayed();compose.onNodeWithText("SPUNTO_PLUGIN_TELEGRAM_BOT_TOKEN").assertIsDisplayed()
  compose.onNodeWithText("synthetic-private-placeholder").assertDoesNotExist();compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
  compose.onNodeWithText("Manage settings in the web app · owner sign-in").assertIsNotEnabled()
  compose.runOnIdle {assertEquals(0,links);assertFalse(page.json().toString().contains("synthetic-private-placeholder"))}
 }
 @Test fun permissionsHeadingsAndPendingAcceptUseProductionDialog() {
  val plugin=NativePluginFacts(JsonObject(row()+ ("granted" to JsonArray(emptyList()))))
  var accepts=0
  compose.setContent {SpuntoTheme {NativePluginPermissionDialog(plugin,true,true,"",{},{accepts++},{})}}
  compose.onNodeWithText("voice-memos now also wants to…").assertExists()
  compose.onNodeWithText("It will").assertExists();compose.onNodeWithText("AI and where note text goes").assertExists();compose.onNodeWithText("Where it shows up").assertExists()
  compose.onNodeWithText("Saving…").assertIsNotEnabled();compose.runOnIdle {assertEquals(0,accepts)}
 }
 @Test fun renderedUndoAppearsOnlyAfterRealControllerAcknowledgement()=runBlocking {
  val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
  val config=AuthConfig("https://synthetic.invalid","","synthetic-device","Synthetic")
  val fence=PluginPublicationFence();val ticket=fence.bind(config.serverUrl,"a")
  val initial=fence.publish(ticket,manifest(3),catalog(row(),3))!!
  val state=MutableStateFlow(initial);var serverRow=row();var revision=3
  val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();var calls=0;val accepted=mutableListOf<List<String>>()
  val api=object:NativePluginBackend {
   override suspend fun manifest()=this@NativePluginControlsTest.manifest(revision)
   override suspend fun catalog()=this@NativePluginControlsTest.catalog(serverRow,revision)
   override suspend fun ai()=JsonObject(emptyMap())
   override suspend fun settings(id:String)=JsonObject(emptyMap())
   override suspend fun toggle(id:String,on:Boolean,permissions:List<String>):JsonObject {
    calls++;accepted+=permissions
    if(calls==1) {entered.complete(Unit);release.await()}
    revision++;serverRow=JsonObject(serverRow+mapOf("switch" to JsonPrimitive(if(on) "on" else "off"),"state" to JsonPrimitive(if(on) "waiting" else "available")))
    return JsonObject(serverRow+("plugins_rev" to JsonPrimitive(revision)))
   }
  }
  val actions=withContext(Dispatchers.Main) {NativePluginManagerActions(scope,{config},{true},{"a"},{api},{nativePluginCatalog(state.value.catalog,state.value.manifest)},
   {_,snapshot -> fence.publish(ticket,snapshot.manifest,snapshot.catalog)?.let {state.value=it;true} ?: false},
   {_,_,receipt -> fence.acknowledge(ticket,receipt)?.let {state.value=it;true} ?: false},
   {_,_ -> NativePluginPage()},{_,_,_ ->},{_,_ ->},{})}
  try {
   compose.setContent {SpuntoTheme {val current by state.collectAsState();val busy by actions.busy.collectAsState();val undo by actions.undo.collectAsState();Column {
    NativePluginRow(nativePluginCatalog(current.catalog,current.manifest).rows.single(),true,busy,{actions.toggle("voice-memos",it)},{},{},{})
    NativePluginUndo(undo,true,busy) {actions.undo {fail("No new scopes")}}
   }}}
   compose.onNodeWithContentDescription("voice-memos wanted on").performClick();withTimeout(10_000) {entered.await()}
   compose.onNodeWithText("Undo").assertDoesNotExist();compose.onNodeWithContentDescription("voice-memos wanted on").assertIsNotEnabled()
   release.complete(Unit);compose.waitUntil(10_000) {actions.undo.value!=null && !actions.busy.value}
   compose.onNodeWithContentDescription("voice-memos wanted on").assertIsOff();compose.onNodeWithText("Undo").performClick()
   compose.waitUntil(10_000) {calls==2 && !actions.busy.value}
   compose.onNodeWithContentDescription("voice-memos wanted on").assertIsOn();compose.onNodeWithText("Undo").assertDoesNotExist()
   compose.runOnIdle {assertEquals(listOf(emptyList<String>(),listOf("ai.transcribe")),accepted)}
  } finally {scope.cancel()}
 }
}
