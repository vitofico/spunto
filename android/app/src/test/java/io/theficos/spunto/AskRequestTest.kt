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
}
