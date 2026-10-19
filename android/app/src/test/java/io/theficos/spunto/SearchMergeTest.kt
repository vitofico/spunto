package io.theficos.spunto
import io.theficos.spunto.core.model.Note
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
class SearchMergeTest {
 @get:Rule val timeout=org.junit.rules.Timeout(10,java.util.concurrent.TimeUnit.MINUTES)
 @Test fun remoteHitsPreserveUnsyncedBodiesAndNewLocalMatches() {
  val edited=Note("edited","New local wording",pending=true)
  val created=Note("created","New capture",pending=true)
  val trashed=Note("trash","Hidden",pending=true,trashed=true)
  val results=mergeSearchResults(listOf(edited,created),listOf(Note("edited","Old server wording"),Note("trash","Old visible")),listOf(edited,created,trashed))
  assertEquals(listOf(edited,created),results)
 }
}
