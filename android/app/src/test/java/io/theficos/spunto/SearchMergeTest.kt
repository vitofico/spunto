package io.theficos.spunto
import io.theficos.spunto.core.model.Note
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import kotlinx.serialization.json.*
class SearchMergeTest {
 @get:Rule val timeout=org.junit.rules.Timeout(10,java.util.concurrent.TimeUnit.MINUTES)
 @Test fun remoteHitsPreserveUnsyncedBodiesAndNewLocalMatches() {
  val edited=Note("edited","New local wording",pending=true)
  val created=Note("created","New capture",pending=true)
  val trashed=Note("trash","Hidden",pending=true,trashed=true)
  val results=mergeSearchResults(listOf(edited,created),listOf(Note("edited","Old server wording"),Note("trash","Old visible")),listOf(edited,created,trashed))
  assertEquals(listOf(edited,created),results)
 }
 @Test fun loadedCursorPagesKeepAllSyncedHitsAndPendingBodiesExactlyOnce() {
  fun wire(index:Int,meaning:Boolean=false)=buildJsonObject {put("id","note-$index");put("body","Synthetic match $index");put("title","Synthetic match $index");put("match",if(meaning) "meaning" else "keyword")}
  val first=buildJsonObject {put("hits",JsonArray((1..50).map {wire(it)}));put("next_cursor","synthetic-page-two")}
  val second=buildJsonObject {put("hits",JsonArray((50..55).map {wire(it,true)}));put("next_cursor",JsonNull)}
  val pages=SearchPages().append(first)
  assertEquals("synthetic-page-two",pages.cursor);assertEquals(50,pages.hits.size)
  val all=pages.append(second)
  assertNull(all.cursor);assertEquals(55,all.hits.size);assertEquals((1..55).map {"note-$it"},all.hits.map {it.id})
  val edited=Note("note-51","New local wording",pending=true)
  val capture=Note("new","New capture",pending=true)
  val results=mergeSearchResults(listOf(edited,capture),all.hits,listOf(edited,capture))
  assertEquals(56,results.size);assertEquals(edited,results.single {it.id==edited.id});assertEquals(capture,results.last());assertTrue("note-55" in all.meaning)
 }
}
