package io.theficos.spunto

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import io.theficos.spunto.core.design.SpuntoTheme
import kotlinx.serialization.json.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Production catalog layout with synthetic rows only; no account, replica or network. */
class PluginCatalogScrollTest {
 @get:Rule val compose=createComposeRule()
 @get:Rule val timeout=org.junit.rules.Timeout(5,java.util.concurrent.TimeUnit.MINUTES)
 private fun catalog(generation:Int=0)=nativePluginCatalog(buildJsonObject {
  put("plugins_rev",generation+1)
  put("plugins",JsonArray(pluginGroups.flatMap {group -> (0 until 8).map {index -> buildJsonObject {
   put("id","test-$group-${'a'+index}");put("name","Synthetic $group $index")
   put("description","Synthetic plugin with settings and declared access. Revision $generation.")
   put("kind","server");put("category",group);put("api","1.0");put("version","1.0.0")
   put("switch",if(index%2==0) "on" else "off");put("state",if(index%2==0) "enabled" else "available")
   put("pinned_by_env",0);put("can_enable",true);put("origin","bundled")
   put("permissions",JsonArray(emptyList()));put("granted",JsonArray(emptyList()))
  }}}))
 },JsonObject(emptyMap()))
 private fun list()=compose.onNode(hasScrollAction())
 private fun variedCatalog(generation:Int)=catalog(generation).let {original ->
  val rows=original.rows.filterIndexed {index,_ -> generation%2==0 || index%5!=0}.mapIndexed {index,plugin ->
   val state=listOf("enabled","waiting","failed","removed","available","disabled")[index%6]
   NativePluginFacts(JsonObject(plugin.raw+mapOf(
    "kind" to JsonPrimitive(if(index%7==0) "device" else "server"),
    "description" to JsonPrimitive(plugin.description.repeat(1+index%4)),
    "state" to JsonPrimitive(state),"switch" to JsonPrimitive(if(index%2==0 && state!="removed") "on" else "off"),
    "pinned_by_env" to JsonPrimitive(if(index%5==0) 1 else 0),
    "permissions" to JsonArray(listOf("notes.read","ai.chat","net.fetch","schedule").map(::JsonPrimitive)),
    "granted" to JsonArray(listOf(JsonPrimitive("notes.read"))),
    "contributes" to Json.parseToJsonElement("""{"schedules":[{"weekday":"monday","time":"10:15"}]}"""),
    "default" to JsonPrimitive("on-with-ai"),"data_count" to JsonPrimitive(214)
   )))
  }
  original.copy(rows=rows)
 }
 private fun scheduled(plugin:NativePluginFacts,weekday:String,time:String)=NativePluginFacts(JsonObject(plugin.raw+mapOf(
  "settings" to Json.parseToJsonElement("""{"weekday":"monday","time":"10:15"}"""),
  "contributes" to buildJsonObject {put("schedules",buildJsonArray {add(buildJsonObject {put("weekday",weekday);put("time",time)})})}
 )))
 private fun flingBothWays(bottomRow:String="Synthetic data 7",onFling:(Int)->Unit={}):Set<Float> {
  val positions=mutableSetOf<Float>()
  val previousAutoAdvance=compose.mainClock.autoAdvance
  compose.mainClock.autoAdvance=false
  try {
   repeat(20) {step ->
    if(step==6 || step==16) {
     compose.mainClock.autoAdvance=true
     if(step==6) {
      list().performScrollToNode(hasText(bottomRow))
      list().performSemanticsAction(SemanticsActions.ScrollBy) {it(0f,10_000f)}
     } else list().performScrollToIndex(0)
     compose.waitForIdle()
     val range=list().fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
     if(step==6) assertEquals("Bottom edge must be reached before overscroll",range.maxValue(),range.value(),0f)
     else assertEquals("Top edge must be reached before overscroll",0f,range.value(),0f)
     compose.mainClock.autoAdvance=false
    }
    list().performTouchInput {if(step<10) swipeUp(durationMillis=80) else swipeDown(durationMillis=80)}
    // Render each fling, then interrupt it before it settles, including at both edges.
    compose.mainClock.advanceTimeBy(32)
    onFling(step)
    compose.mainClock.advanceTimeBy(32)
    positions+=list().fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
   }
  } finally {
   compose.mainClock.autoAdvance=previousAutoAdvance
  }
  compose.waitForIdle()
  return positions
 }

 @Test fun rapidFlingsAcrossGroupsAndBackKeepRowsAndControlsUsable() {
  val projection=catalog();var toggled:String?=null
  assertTrue(projection.valid);assertEquals(48,projection.rows.size)
  compose.setContent {SpuntoTheme {
   NativePluginCatalogList(projection,JsonObject(emptyMap()),true,false,null,{plugin,_ -> toggled=plugin.id},{},{},{},{})
  }}
  repeat(4) {assertTrue("Flings must render different list positions",flingBothWays().size>3)}
  list().performScrollToNode(hasText("Synthetic data 7"))
  compose.onNodeWithText("Synthetic data 7").assertIsDisplayed()
  compose.onNodeWithContentDescription("Synthetic data 7 wanted on").performClick()
  compose.runOnIdle {assertEquals("test-data-h",toggled)}
  list().performScrollToIndex(0)
  compose.onNodeWithText("All 48").assertIsDisplayed()
 }

 @Test fun refreshedAndFilteredCatalogSurvivesRepeatedFastScrolls() {
  val projection=mutableStateOf(catalog())
  compose.setContent {SpuntoTheme {
   NativePluginCatalogList(projection.value,JsonObject(emptyMap()),true,false,null,{_,_ ->},{},{},{},{})
  }}
  compose.onNodeWithText("On 24").performClick()
  repeat(4) {generation ->
   flingBothWays("Synthetic data 6")
   compose.runOnIdle {projection.value=catalog(generation+1)}
  }
  list().performScrollToNode(hasText("Synthetic data 6"))
  compose.onNodeWithText("Synthetic data 6").assertIsDisplayed()
  compose.onNodeWithText("Synthetic data 7").assertDoesNotExist()
  list().performScrollToIndex(0)
  compose.onNodeWithText("All 48").performClick()
  list().performScrollToNode(hasText("Synthetic data 7"))
  compose.onNodeWithText("Synthetic data 7").assertIsDisplayed()
 }

 @Test fun variedRowsAndRefreshDuringActiveFlingsSurviveBothOverscrollEdges() {
  val projection=mutableStateOf(variedCatalog(0))
  val online=mutableStateOf(true)
  val ai=mutableStateOf(JsonObject(emptyMap()))
  compose.setContent {SpuntoTheme {
   NativePluginCatalogList(projection.value,ai.value,online.value,false,null,{_,_ ->},{},{},{},{})
  }}
  val summary=Json.parseToJsonElement("""{"profiles":[],"bindings":{}}""").jsonObject
  repeat(3) {cycle ->
   val positions=flingBothWays {step ->
    compose.runOnUiThread {
     projection.value=variedCatalog(cycle*20+step+1)
     online.value=step%2==0
     ai.value=if(step%2==0) summary else JsonObject(emptyMap())
    }
   }
   assertTrue("Refresh stress must include actual scrolling",positions.size>3)
  }
  compose.runOnIdle {projection.value=catalog();online.value=true}
  list().performScrollToNode(hasText("Synthetic data 7"))
  compose.onNodeWithText("Synthetic data 7").assertIsDisplayed()
  list().performScrollToIndex(0)
  compose.onNodeWithText("All 48").assertIsDisplayed()
 }

 @Test fun scheduleLiteralsAndTemplatesRenderOnAndroidAndMalformedValuesStayUnavailable() {
  val plugin=catalog().rows.last()
  val expected=listOf("Monday 10:15 (server time) · pauses while off or waiting")
  assertEquals(expected,pluginScheduleFacts(scheduled(plugin,"monday","10:15")))
  assertEquals(expected,pluginScheduleFacts(scheduled(plugin,"{weekday}","{time}")))
  assertEquals(listOf("Schedule details unavailable"),pluginScheduleFacts(scheduled(plugin,"{Weekday}","{time}")))
  assertEquals(listOf("Schedule details unavailable"),pluginScheduleFacts(scheduled(plugin,"{missing}","{time}")))
  assertEquals(listOf("Schedule details unavailable"),pluginScheduleFacts(scheduled(plugin,"{weekday}","{time")))
 }

 @Test fun rapidScrollReachesScheduledPluginInLastCategoryAndReturnsToHeader() {
  val original=catalog()
  val plugin=scheduled(original.rows.last(),"{weekday}","{time}")
  val projection=original.copy(rows=original.rows.dropLast(1)+plugin)
  val schedule="Monday 10:15 (server time) · pauses while off or waiting"
  compose.setContent {SpuntoTheme {
   NativePluginCatalogList(projection,JsonObject(emptyMap()),true,false,null,{_,_ ->},{},{},{},{})
  }}
  compose.onNodeWithText(schedule).assertDoesNotExist()
  var sawScheduledRow=false
  flingBothWays {step ->
   if(step==6) {
    compose.onNodeWithText(plugin.name).assertIsDisplayed()
    compose.onNodeWithText(schedule).assertIsDisplayed()
    sawScheduledRow=true
   }
  }
  assertTrue("Fast scroll must reach the scheduled plugin",sawScheduledRow)
  list().performScrollToIndex(0)
  compose.onNodeWithText("All 48").assertIsDisplayed()
 }
}
