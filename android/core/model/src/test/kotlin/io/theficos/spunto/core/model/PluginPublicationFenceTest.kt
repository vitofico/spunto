package io.theficos.spunto.core.model

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PluginPublicationFenceTest {
 private fun manifest(rev:Int,on:Boolean)=Json.parseToJsonElement("""{"plugins_rev":$rev,"plugins":${if(on) "[{\"id\":\"dictation\",\"enabled\":true}]" else "[]"}}""").jsonObject
 private fun catalog(rev:Int,on:Boolean)=Json.parseToJsonElement("""{"plugins_rev":$rev,"plugins":[{"id":"dictation","switch":"${if(on) "on" else "off"}"}]}""").jsonObject
 @Test fun stagingIsPrivateAndAnOldStageCannotCommitIntoAnotherOwner() {
  val fence=PluginPublicationFence();val owner=fence.transitionOwner("owner-a","https://synthetic.invalid","a")
  fence.publish(owner,manifest(3,true),catalog(3,true))
  val stage=fence.stageAcknowledgement(owner,Json.parseToJsonElement("""{"id":"dictation","switch":"off","plugins_rev":4}""").jsonObject)!!
  assertEquals(3L,fence.current(owner)!!.revision);assertEquals(1,fence.current(owner)!!.manifest["plugins"]!!.jsonArray.size)
  val replacement=fence.transitionOwner("owner-b","https://synthetic.invalid","a")
  assertNull(fence.commit(stage));assertNull(fence.current(replacement))
 }
 @Test fun exactReceiptFloorRejectsHeldOlderResponsesAndAllowsLaterRealToggle() {
  val fence=PluginPublicationFence();val owner=fence.bind("https://synthetic.invalid/","epoch-a")
  assertNotNull(fence.publish(owner,manifest(3,true),catalog(3,true)))
  val row=Json.parseToJsonElement("""{"id":"dictation","switch":"off","plugins_rev":4}""").jsonObject
  val off=fence.acknowledge(owner,row)!!;assertEquals(4L,off.revision);assertEquals(0,off.manifest["plugins"]!!.jsonArray.size)
  assertNull(fence.publish(owner,manifest(3,true),catalog(3,true)))
  assertNull(fence.publish(owner,manifest(4,false),catalog(3,false)))
  assertNotNull(fence.publish(owner,manifest(4,false),catalog(4,false)))
  assertNotNull(fence.publish(owner,manifest(5,true),catalog(5,true)))
  assertNull(fence.acknowledge(owner,row))
 }
 @Test fun revisionlessReceiptStaysOffThroughLocalEchoAndOldResponse() {
  val fence=PluginPublicationFence();val owner=fence.bind("https://synthetic.invalid","a")
  fence.publish(owner,manifest(3,true),catalog(3,true))
  val off=fence.acknowledge(owner,catalog(3,false)["plugins"]!!.jsonArray.first().jsonObject)!!
  fence.publish(owner,off.manifest,off.catalog)
  assertNull(fence.publish(owner,manifest(3,true),catalog(3,true)))
  assertNotNull(fence.publish(owner,manifest(4,false),catalog(4,false)))
 }
 @Test fun rootAndEpochResetRejectOldTicketsWithoutCarryingFloorOrIntent() {
  val fence=PluginPublicationFence();val old=fence.bind("https://one.synthetic","a")
  fence.publish(old,manifest(9,true),catalog(9,true))
  val restored=fence.bind("https://one.synthetic","b")
  assertNull(fence.publish(old,manifest(10,true),catalog(10,true)));assertNotNull(fence.publish(restored,manifest(1,false),catalog(1,false)))
  val other=fence.bind("https://two.synthetic","b")
  assertNull(fence.current(restored));assertNull(fence.publish(restored,manifest(10,true),catalog(10,true)));assertNotNull(fence.publish(other,manifest(0,true),catalog(0,true)))
 }
 @Test fun publicCachesDropSecretValuesAndUnknownSecretStatusFields() {
  val fence=PluginPublicationFence();val owner=fence.bind("https://synthetic.invalid","a")
  val catalog=Json.parseToJsonElement("""{"plugins_rev":1,"plugins":[{"id":"telegram","switch":"off","settings":{"bot_token":"synthetic-private-placeholder","language":"it"},"secrets":{"bot_token":{"set":true,"source":"environment","value":"synthetic-private-placeholder"}}}]}""").jsonObject
  val manifest=Json.parseToJsonElement("""{"plugins_rev":1,"plugins":[{"id":"telegram","settings":{"properties":{"bot_token":{"x-secret":true}}},"settings_values":{"bot_token":"synthetic-private-placeholder","language":"it"}}]}""").jsonObject
  val publication=fence.publish(owner,manifest,catalog)!!
  assertFalse(publication.catalog.toString().contains("synthetic-private-placeholder"));assertFalse(publication.manifest.toString().contains("synthetic-private-placeholder"))
  assertEquals("it",publication.catalog["plugins"]!!.jsonArray.single().jsonObject["settings"]!!.jsonObject["language"]!!.jsonPrimitive.content)
 }
}
