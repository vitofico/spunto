package io.theficos.spunto

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

class AskRequestTest {
 @get:Rule val timeout=Timeout(10,TimeUnit.MINUTES)

 @Test fun pendingFailureClearsProgressAndAllowsSuccessfulRetry()=runBlocking {
  var asking=true
  var completions=0
  var result:JsonElement?=null
  val started=CompletableDeferred<Unit>()
  val response=CompletableDeferred<JsonElement?>()
  val failed=launch {
   try { completeAskRequest({ started.complete(Unit);response.await() },{asking=false;completions++},{result=it}) }
   catch(expected:IOException) { }
  }
  started.await()
  assertTrue("The pending request keeps repeat Ask disabled",asking)
  assertNull(result)
  response.completeExceptionally(IOException("Synthetic provider timeout"))
  failed.join()
  assertFalse("Failure clears Reading your notes",asking)
  assertEquals(1,completions)
  assertNull(result)

  asking=true
  val answer=buildJsonObject { put("answer","Synthetic retry answer") }
  completeAskRequest({answer},{asking=false;completions++},{result=it})
  assertFalse(asking)
  assertEquals(2,completions)
  assertEquals(answer,result)
 }

 @Test fun pendingCancellationPropagatesAndAllowsRetry()=runBlocking {
  var asking=true
  var completed=0
  var results=0
  var cancellationPropagated=false
  val started=CompletableDeferred<Unit>()
  val job=launch {
   try { completeAskRequest({started.complete(Unit);awaitCancellation()},{asking=false;completed++},{results++}) }
   catch(expected:CancellationException) { cancellationPropagated=true;throw expected }
  }
  started.await()
  assertTrue(asking)
  job.cancelAndJoin()
  assertTrue(job.isCancelled)
  assertTrue(cancellationPropagated)
  assertFalse(asking)
  assertEquals(1,completed)
  assertEquals(0,results)

  asking=true
  completeAskRequest({JsonObject(emptyMap())},{asking=false;completed++},{results++})
  assertFalse(asking)
  assertEquals(2,completed)
  assertEquals(1,results)
 }

 @Test fun MissingAuthenticationStillCompletesWithoutPublishingAnswer()=runBlocking {
  var asking=true
  var results=0
  completeAskRequest({null},{asking=false},{results++})
  assertFalse(asking)
  assertEquals(0,results)
 }

 @Test fun InvalidAnswerCallbackStillClearsProgress()=runBlocking {
  var asking=true
  try {
   completeAskRequest({JsonPrimitive("Synthetic malformed answer")},{asking=false},{it.jsonObject})
   fail("Malformed output must be reported by the normal task error path")
  } catch(expected:IllegalArgumentException) { }
  assertFalse(asking)
 }

 private fun startedConversation()=buildJsonObject {put("ui",1);put("items",JsonArray(listOf(buildJsonObject {put("type","command");put("id","ask");put("title","Ask a follow-up");put("args",buildJsonObject {put("conversation_id","allocated");put("after_turn",0)})})))}
 @Test fun allocatesBeforeFirstInferenceAndPublishesTheHandle()=runBlocking {
  val commands=mutableListOf<Pair<String,JsonObject>>()
  var handle:JsonObject?=null
  val answer=buildJsonObject {put("type","markdown");put("text","First answer")}
  val result=conversationAskRequest("First question?",null,{id,args -> commands+=id to args;if(args["start"]==JsonPrimitive(true)) startedConversation() else answer},{true},{handle=it})
  assertEquals(listOf("ask","ask"),commands.map {it.first})
  assertEquals("allocated",handle!!["conversation_id"]!!.jsonPrimitive.content)
  assertEquals(0,commands[1].second["after_turn"]!!.jsonPrimitive.int)
  assertEquals("First question?",commands[1].second["question"]!!.jsonPrimitive.content)
  assertEquals(answer,result)
 }
 @Test fun lateAllocationAfterCancellationIsClearedBeforeInference()=runBlocking {
  val commands=mutableListOf<String>()
  val started=CompletableDeferred<Unit>();val response=CompletableDeferred<JsonElement>()
  var active=true;var published=false
  val job=launch {
   conversationAskRequest("Discard me",null,{id,args -> commands+=id;if(args["start"]==JsonPrimitive(true)) {started.complete(Unit);response.await()} else JsonObject(emptyMap())},{active},{published=true})
  }
  started.await();active=false;job.cancel();response.complete(startedConversation());job.join()
  assertEquals(listOf("ask","clear-conversation"),commands)
  assertFalse(published)
  assertTrue(job.isCancelled)
 }
 @Test fun followupUsesExistingHandleWithoutAnotherAllocation()=runBlocking {
  val continuation=buildJsonObject {put("conversation_id","existing");put("after_turn",4)}
  val commands=mutableListOf<Pair<String,JsonObject>>()
  conversationAskRequest("Follow-up?",continuation,{id,args -> commands+=id to args;JsonObject(emptyMap())},{true},{fail("Existing handle must be reused")})
  assertEquals(listOf("ask"),commands.map {it.first})
  assertEquals(4,commands.single().second["after_turn"]!!.jsonPrimitive.int)
 }

 @Test fun oldServerReceivesExactlyOneLegacyQuestionWithoutConversationArguments()=runBlocking {
  val requests=mutableListOf<Pair<String,JsonObject>>()
  val answer=buildJsonObject {put("type","markdown");put("text","Legacy answer with sources")}
  val result=conversationAskRequest("Where is coffee?",null,{command,args ->
   requests+=command to args
   require(command=="ask" && args.keys==setOf("question")) {"Old server rejects conversation arguments"}
   answer
  },{true},{fail("Old server has no conversation handle")},conversationSupported=false)
  assertEquals(listOf("ask" to buildJsonObject {put("question","Where is coffee?")}),requests)
  assertEquals(answer,result)
 }
 @Test fun cancelledLegacyQuestionDoesNotSendOrAllocate()=runBlocking {
  val result=conversationAskRequest("Discarded",null,{_,_ -> fail("No request after departure");JsonNull},{false},{fail("No legacy handle")},conversationSupported=false)
  assertNull(result)
 }
 @Test fun advertisedConversationFailureDoesNotRetryAsALegacyQuestion()=runBlocking {
  val requests=mutableListOf<Pair<String,JsonObject>>()
  try {
   conversationAskRequest("First question?",null,{command,args ->requests+=command to args;throw IOException("Synthetic start failure")},{true},{fail("Failed allocation has no handle")},conversationSupported=true)
   fail("The original start failure must be reported")
  } catch(expected:IOException) { }
  assertEquals(listOf("ask" to buildJsonObject {put("start",true)}),requests)
 }
 @Test fun malformedAdvertisedStartDoesNotSilentlyDowngrade()=runBlocking {
  val requests=mutableListOf<Pair<String,JsonObject>>()
  try {
   conversationAskRequest("First question?",null,{command,args ->requests+=command to args;buildJsonObject {put("type","text");put("text","Malformed start")}}, {true},{fail("Malformed allocation has no handle")},conversationSupported=true)
   fail("Malformed start must remain a failure")
  } catch(expected:IllegalStateException) { }
  assertEquals(listOf("ask" to buildJsonObject {put("start",true)}),requests)
 }
}
