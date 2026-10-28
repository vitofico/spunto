package io.theficos.spunto

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SearchPaginationTest {
 @get:Rule val timeout=org.junit.rules.Timeout(30,java.util.concurrent.TimeUnit.SECONDS)
 private fun page(id:String,cursor:String?=null)=buildJsonObject {
  put("hits",buildJsonArray {add(buildJsonObject {put("id",id);put("body","Synthetic result");put("title","Synthetic result")})})
  put("next_cursor",cursor?.let(::JsonPrimitive) ?: JsonNull)
 }
 @Test fun nextPageIsRequestedOnlyOnDemandAndRetriesTheSameCursor()=runBlocking {
  val more=Channel<Unit>();val shown=Channel<SearchPages>(Channel.UNLIMITED);val requested=mutableListOf<String?>();var failures=0;var attempt=0
  val job=launch {searchServerPages(request={cursor ->requested+=cursor;if(cursor==null) page("first","page-two") else if(attempt++==0) error("Synthetic network interruption") else page("second")},awaitMore={more.receive()},show={shown.trySend(it)},failed={failures++})}
  assertEquals(listOf("first"),shown.receive().hits.map {it.id});assertEquals(listOf<String?>(null),requested)
  more.send(Unit);yield();assertEquals(1,failures);assertEquals(listOf(null,"page-two"),requested)
  more.send(Unit);assertEquals(listOf("first","second"),shown.receive().hits.map {it.id});job.join()
  assertEquals(listOf(null,"page-two","page-two"),requested)
 }
 @Test fun changingQueryDateOrAccountCancelsOldPageWithoutPublishingLateResponse()=runBlocking {
  for(change in listOf("query","dates","account")) {
   val started=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();val shown=mutableListOf<String>();var oldRequests=0
   val old=launch {searchServerPages(request={oldRequests++;started.complete(Unit);withContext(NonCancellable) {release.await()};page("old-$change","old-cursor")},awaitMore={error("Cancelled query requested another page")},show={shown+=it.hits.map {note ->note.id}},failed={throw it})}
   started.await();old.cancel()
   searchServerPages(request={page("new-$change")},awaitMore={error("No cursor")},show={shown+=it.hits.map {note ->note.id}},failed={throw it})
   release.complete(Unit);old.join()
   assertEquals(listOf("new-$change"),shown);assertEquals(1,oldRequests)
  }
 }
 @Test fun leavingWhileWaitingForMoreDoesNotFetchAnotherPage()=runBlocking {
  val waiting=CompletableDeferred<Unit>();var requests=0
  val job=launch {searchServerPages(request={requests++;page("first","next")},awaitMore={waiting.complete(Unit);awaitCancellation()},show={},failed={throw it})}
  waiting.await();job.cancelAndJoin();assertEquals(1,requests)
 }
}
