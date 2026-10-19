package io.theficos.spunto

import kotlinx.coroutines.Job
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test

class NativePluginCommandOwnerTest {
 @Test fun heldSameAuthRestoreAndCloseReopenCannotPublishOrClearNewPending() {
  var identity=PluginCommandIdentity("same-owner","a","plugin-one")
  val owner=NativePluginCommandOwner(identity={identity})
  val callbacks=mutableListOf<Pair<(JsonObject)->Unit,()->Unit>>()
  val results=mutableListOf<JsonObject>()
  fun start()=owner.start({result,done -> callbacks+=result to done;Job()},{results+=it})
  assertTrue(start())
  identity=identity.copy(epoch="b");owner.reset();assertTrue(start())
  callbacks[0].first(JsonObject(emptyMap()));callbacks[0].second()
  assertTrue(owner.pending.value);assertTrue(results.isEmpty())
  owner.reset();assertTrue(start()) // Same plugin ID, distinct selection attempt.
  callbacks[1].first(JsonObject(emptyMap()));callbacks[1].second()
  assertTrue(owner.pending.value);assertTrue(results.isEmpty())
  callbacks[2].first(JsonObject(emptyMap()));callbacks[2].second()
  assertEquals(1,results.size);assertFalse(owner.pending.value)
 }
 @Test fun completedRequestWaitsForCommittedEpochValidationAndLateValidationCannotClearAnotherAttempt() {
  var identity=PluginCommandIdentity("same-owner","a","plugin-one")
  val validations=mutableListOf<(Boolean)->Unit>()
  val requestCallbacks=mutableListOf<Pair<(JsonObject)->Unit,()->Unit>>()
  val results=mutableListOf<JsonObject>()
  val owner=NativePluginCommandOwner(identity={identity},validate={_,done -> validations+=done;Job()})
  fun start()=owner.start({result,done -> requestCallbacks+=result to done;Job()},{results+=it})
  start();requestCallbacks[0].first(JsonObject(emptyMap()));requestCallbacks[0].second()
  assertTrue(owner.pending.value)
  // The DB has restored even while the UI identity field still awaits its observation.
  validations[0](false);assertFalse(owner.pending.value);assertTrue(results.isEmpty())
  start();requestCallbacks[1].first(JsonObject(emptyMap()));requestCallbacks[1].second()
  owner.reset();identity=identity.copy(epoch="b");start()
  validations[1](false);assertTrue(owner.pending.value);assertTrue(results.isEmpty())
  requestCallbacks[2].first(JsonObject(emptyMap()));requestCallbacks[2].second();validations[2](true)
  assertFalse(owner.pending.value);assertEquals(1,results.size)
 }

}
