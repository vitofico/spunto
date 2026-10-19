package io.theficos.spunto

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import io.theficos.spunto.core.model.PluginPublicationFence
import io.theficos.spunto.core.model.Note
import io.theficos.spunto.core.plugins.ManifestCache
import io.theficos.spunto.data.local.PluginOutputEntity
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Isolated preferences, real cache and synchronized fence, no owner DB/surfaces/network. */
class NativePluginPublicationTest {
 private fun manifest(rev:Int,on:Boolean)=Json.parseToJsonElement("""{"plugins_rev":$rev,"plugins":${if(on) "[{\"id\":\"dictation\",\"enabled\":true}]" else "[]"}}""").jsonObject
 private fun catalog(rev:Int,on:Boolean)=Json.parseToJsonElement("""{"plugins_rev":$rev,"plugins":[{"id":"dictation","switch":"${if(on) "on" else "off"}"}]}""").jsonObject
 @Test fun heldOldStoreAndSurfaceCallbackCannotWinAfterNewAcknowledgedOff() {
  val context=InstrumentationRegistry.getInstrumentation().targetContext
  val prefix="test-plugin-publication-${System.nanoTime()}-"
  val isolated=object:ContextWrapper(context) {override fun getSharedPreferences(name:String,mode:Int)=context.getSharedPreferences(prefix+name,mode)}
  val fence=PluginPublicationFence();val owner=fence.bind("https://synthetic.invalid","a")
  fence.publish(owner,manifest(3,true),catalog(3,true))
  val entered=CountDownLatch(1);val release=CountDownLatch(1);val blocked=AtomicBoolean(false)
  val cache=ManifestCache(isolated,fence) {json -> if(json["plugins_rev"]?.jsonPrimitive?.intOrNull==3 && blocked.compareAndSet(false,true)) {entered.countDown();check(release.await(10,TimeUnit.SECONDS))}}
  val enabled=AtomicBoolean(false);val offFinished=CountDownLatch(1);val attempted=CountDownLatch(1)
  val old=Thread {cache.adopt(owner) {enabled.set(cache.enabled("dictation"))}}.apply {start()}
  assertTrue(entered.await(10,TimeUnit.SECONDS))
  val newer=Thread {attempted.countDown();fence.acknowledge(owner,Json.parseToJsonElement("""{"id":"dictation","switch":"off","plugins_rev":4}""").jsonObject);cache.adopt(owner) {enabled.set(cache.enabled("dictation"))};offFinished.countDown()}.apply {start()}
  assertTrue(attempted.await(10,TimeUnit.SECONDS));assertFalse(offFinished.await(100,TimeUnit.MILLISECONDS)) // Store/callback still owns the publication lock.
  release.countDown();old.join(10_000);newer.join(10_000);assertFalse(old.isAlive);assertFalse(newer.isAlive)
  assertFalse(enabled.get());assertFalse(cache.enabled("dictation"));assertEquals(4,cache.value["plugins_rev"]!!.jsonPrimitive.int)
  assertEquals(cache.value.toString(),isolated.getSharedPreferences("plugin-manifest",Context.MODE_PRIVATE).getString("manifest",null))
  // A delayed old consumer now adopts the current Off, including its synchronous side effect.
  cache.adopt(owner) {enabled.set(cache.enabled("dictation"))};assertFalse(enabled.get())
  isolated.getSharedPreferences("plugin-manifest",Context.MODE_PRIVATE).edit().clear().commit()
 }
 @Test fun unpairedBootstrapStillRunsGuardedStaticSurfaceCallback() {
  val context=InstrumentationRegistry.getInstrumentation().targetContext
  val isolated=object:ContextWrapper(context) {override fun getSharedPreferences(name:String,mode:Int)=context.getSharedPreferences("test-bootstrap-${System.nanoTime()}-$name",mode)}
  val cache=ManifestCache(isolated,PluginPublicationFence());var called=false
  cache.withCurrentPublication {called=true;assertTrue(cache.enabled("quick-capture"));assertTrue(cache.enabled("dictation"))}
  assertTrue(called)
 }
 @Test fun heldSlipConsumerCannotWriteAfterAcknowledgedOffAndDelayedOldOutputsStayCleared() {
  val context=InstrumentationRegistry.getInstrumentation().targetContext
  val prefix="test-slip-publication-${System.nanoTime()}-"
  val isolated=object:ContextWrapper(context) {override fun getSharedPreferences(name:String,mode:Int)=context.getSharedPreferences(prefix+name,mode)}
  val prefs=isolated.getSharedPreferences("device-surfaces",Context.MODE_PRIVATE)
  val fence=PluginPublicationFence();val owner=fence.bind("https://synthetic.invalid","a")
  fence.publish(owner,Json.parseToJsonElement("""{"plugins_rev":3,"plugins":[{"id":"resurface","enabled":true}]}""").jsonObject,Json.parseToJsonElement("""{"plugins_rev":3,"plugins":[{"id":"resurface","switch":"on"}]}""").jsonObject)
  val cache=ManifestCache(isolated,fence)
  val notes=listOf(Note("synthetic-slip","A synthetic remembered note",createdAt=1,updatedAt=1))
  val outputs=listOf(PluginOutputEntity("annotation","synthetic-output","synthetic-slip","""{"kind":"slip","plugin":"resurface"}"""))
  val entered=CountDownLatch(1);val release=CountDownLatch(1);val attempted=CountDownLatch(1);val offFinished=CountDownLatch(1)
  val failure=AtomicReference<Throwable?>(null)
  val write:(String?,String?)->Unit={id,text -> check(prefs.edit().putString("slip-id",id).putString("slip-text",text).commit())}
  val old=Thread {try {cache.withCaptureSurfacePublication(notes,outputs,{}, {id,text -> entered.countDown();check(release.await(10,TimeUnit.SECONDS));write(id,text)})} catch(error:Throwable) {failure.compareAndSet(null,error)}}.apply {start()}
  try {
   assertTrue(entered.await(10,TimeUnit.SECONDS))
   val newer=Thread {try {
    attempted.countDown()
    fence.acknowledge(owner,Json.parseToJsonElement("""{"id":"resurface","switch":"off","plugins_rev":4}""").jsonObject)
    cache.withCaptureSurfacePublication(notes,outputs,{},write)
    offFinished.countDown()
   } catch(error:Throwable) {failure.compareAndSet(null,error)}}.apply {start()}
   try {
    assertTrue(attempted.await(10,TimeUnit.SECONDS));assertFalse(offFinished.await(100,TimeUnit.MILLISECONDS))
    release.countDown();old.join(10_000);newer.join(10_000)
    assertFalse(old.isAlive);assertFalse(newer.isAlive);failure.get()?.let {throw AssertionError("Publication thread failed",it)}
    assertEquals(0L,offFinished.count);assertFalse(cache.enabled("resurface"))
    assertNull(prefs.getString("slip-id",null));assertNull(prefs.getString("slip-text",null))
    // The old output/notes snapshot is still eligible except for its now-current manifest.
    cache.withCaptureSurfacePublication(notes,outputs,{},write)
    assertNull(prefs.getString("slip-id",null));assertNull(prefs.getString("slip-text",null))
   } finally {release.countDown();newer.join(10_000)}
  } finally {release.countDown();old.join(10_000);prefs.edit().clear().commit();isolated.getSharedPreferences("plugin-manifest",Context.MODE_PRIVATE).edit().clear().commit()}
 }
 @Test fun slipPublicationKeepsSelectionFiltersTextLimitAndUnpairedClearing() {
  val context=InstrumentationRegistry.getInstrumentation().targetContext
  val prefix="test-slip-selection-${System.nanoTime()}-"
  val isolated=object:ContextWrapper(context) {override fun getSharedPreferences(name:String,mode:Int)=context.getSharedPreferences(prefix+name,mode)}
  val fence=PluginPublicationFence();val owner=fence.bind("https://synthetic.invalid","a")
  fence.publish(owner,Json.parseToJsonElement("""{"plugins_rev":3,"plugins":[{"id":"resurface","enabled":true}]}""").jsonObject,Json.parseToJsonElement("""{"plugins_rev":3,"plugins":[{"id":"resurface","switch":"on"}]}""").jsonObject)
  val cache=ManifestCache(isolated,fence)
  val notes=listOf(Note("private","Synthetic private",createdAt=1,privateNote=true),Note("trashed","Synthetic trashed",createdAt=1,trashed=true),Note("first","x".repeat(300),createdAt=1),Note("second","Synthetic second",createdAt=1))
  fun output(id:String,plugin:String="resurface",kind:String="annotation",payload:String="""{"kind":"slip","plugin":"$plugin"}""")=PluginOutputEntity(kind,"output-$id",id,payload)
  val outputs=listOf(output("first",payload="malformed"),output("first",kind="view"),output("first",payload="""{"kind":"other","plugin":"resurface"}"""),output("private"),output("trashed"),output("first",plugin="disabled"),output("first"),output("second"))
  var id:String?=null;var text:String?=null;var updated=false
  cache.withCaptureSurfacePublication(notes,outputs,{updated=true},{nextId,nextText -> id=nextId;text=nextText})
  assertTrue(updated);assertEquals("first",id);assertEquals("x".repeat(280),text)
  // A restored owner with no committed pair keeps bootstrap quick/dictate defaults and clears old slips.
  fence.bind("https://synthetic.invalid","restored")
  cache.withCaptureSurfacePublication(notes,outputs,{assertTrue(cache.enabled("quick-capture"));assertTrue(cache.enabled("dictation"))},{nextId,nextText -> id=nextId;text=nextText})
  assertNull(id);assertNull(text)
  isolated.getSharedPreferences("plugin-manifest",Context.MODE_PRIVATE).edit().clear().commit()
 }
}
