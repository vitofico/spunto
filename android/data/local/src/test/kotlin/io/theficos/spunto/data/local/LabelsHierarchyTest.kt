package io.theficos.spunto.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.theficos.spunto.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real isolated SQLite files; no network, owner database or schema changes. */
@RunWith(RobolectricTestRunner::class) @Config(sdk=[28])
class LabelsHierarchyTest {
 @get:Rule val timeout=org.junit.rules.Timeout(10,java.util.concurrent.TimeUnit.MINUTES)
 private lateinit var context:Context
 private lateinit var name:String
 private lateinit var db:SpuntoDatabase
 private lateinit var repository:NotesRepository
 @Before fun setup() {
  context=ApplicationProvider.getApplicationContext();name="label-tree-${System.nanoTime()}.db"
  db=Room.databaseBuilder(context,SpuntoDatabase::class.java,name).allowMainThreadQueries().build();repository=NotesRepository(db)
 }
 @After fun close() {db.close();context.deleteDatabase(name)}
 private suspend fun note(body:String,archived:Boolean=false,privateNote:Boolean=false,trashed:Boolean=false):String {
  val id=uuidV7();repository.putNote(Note(id,body,archived=archived,privateNote=privateNote,trashed=trashed));return id
 }
 @Test fun ancestorsCountEachNoteOnceAcrossParentAndOverlappingChildren()=runBlocking {
  note("Synthetic #Project #Project/Spunto #Project/Spunto")
  note("Synthetic #Project/Spunto #Project/Other")
  note("Synthetic archived #Project/Spunto",archived=true)
  note("Synthetic private #Project/Other",privateNote=true)
  note("Synthetic trash #Project/Excluded",trashed=true)
  val tombstone=note("Synthetic pending row #Project/Purged");repository.dao.putTombstone(TombstoneEntity(tombstone,1))
  val counts=repository.dao.labels().first().associateBy {it.key}
  assertEquals(setOf("project","project/other","project/spunto"),counts.keys)
  assertEquals(3,counts.getValue("project").count);assertEquals("Project",counts.getValue("project").path)
  assertEquals(2,counts.getValue("project/spunto").count);assertEquals(2,counts.getValue("project/other").count)
  assertTrue(counts.getValue("project").count<counts.getValue("project/spunto").count+counts.getValue("project/other").count)
  assertNotNull(repository.dao.get(tombstone));assertTrue(repository.dao.outbox().isEmpty())
 }
 @Test fun implicitFiveSegmentParentsHaveExactCountsAndSameDescendantMembership()=runBlocking {
  val leaf=note("Synthetic #a/b/c/d/e");val sibling=note("Synthetic #a/b/other")
  val rows=repository.dao.labels().first()
  assertEquals(listOf("a","a/b","a/b/c","a/b/c/d","a/b/c/d/e","a/b/other"),rows.map {it.key})
  assertEquals(listOf(2,2,1,1,1,1),rows.map {it.count})
  val matches=repository.dao.filteredNotes(timelineQuery(TimelineSpec(kind=TimelineKind.LABEL,label="a/b")))
  assertEquals(setOf(leaf,sibling),matches.map {it.id}.toSet());assertEquals(rows.first {it.key=="a/b"}.count,matches.size)
 }
 @Test fun archivedNotesLeaveCountsAndLabelViewsLikeTheWebRail()=runBlocking {
  val live=note("Synthetic #Project/Spunto");val archived=note("Synthetic #Project/Spunto #Old",archived=true)
  val rows=repository.dao.labels().first()
  assertEquals(listOf("project" to 1,"project/spunto" to 1),rows.map {it.key to it.count})
  assertEquals(listOf(live),repository.dao.filteredNotes(timelineQuery(TimelineSpec(kind=TimelineKind.LABEL,label="project"))).map {it.id})
  val restored=async {withTimeout(3000) {repository.dao.labels().first {r ->r.any {it.key=="old"}}}}
  repository.dao.put(repository.dao.get(archived)!!.copy(archived=false))
  assertEquals(listOf("old" to 1,"project" to 2,"project/spunto" to 2),restored.await().map {it.key to it.count})
 }
 @Test fun caseFoldedKeysAndDistinctNotesKeepDeterministicDisplayWithoutAccentCollapse()=runBlocking {
  note("Synthetic #Straße/Ideen");note("Synthetic #STRASSE/IDEEN");note("Synthetic #Café/Idee");note("Synthetic #Cafe/Idee")
  val counts=repository.dao.labels().first().associateBy {it.key}
  assertEquals(2,counts.getValue("strasse").count);assertEquals(2,counts.getValue("strasse/ideen").count)
  assertEquals("STRASSE",counts.getValue("strasse").path)
  assertEquals(1,counts.getValue("café").count);assertEquals(1,counts.getValue("cafe").count)
 }
 @Test fun trashAndTombstoneChangesInvalidateObservedHierarchyWithoutDeletingNotes()=runBlocking {
  val live=note("Synthetic #Project/Spunto");val other=note("Synthetic #Project/Other")
  assertEquals(2,repository.dao.labels().first().first {it.key=="project"}.count)
  val changed=async {withTimeout(3000) {repository.dao.labels().first {rows ->rows.firstOrNull {it.key=="project"}?.count==1}}}
  repository.dao.putTombstone(TombstoneEntity(live,1));assertEquals(1,changed.await().first {it.key=="project"}.count)
  val empty=async {withTimeout(3000) {repository.dao.labels().first {it.isEmpty()}}}
  repository.dao.put(repository.dao.get(other)!!.copy(trashed=true));assertTrue(empty.await().isEmpty())
  assertNotNull(repository.dao.get(live));assertNotNull(repository.dao.get(other))
 }
 @Test fun allNotesCountSeesTheSameNotesAsLabelCountsAndFollowsChanges()=runBlocking {
  val live=note("Synthetic plain");note("Synthetic #Project");note("Synthetic archived",archived=true);note("Synthetic trash",trashed=true)
  val purged=note("Synthetic pending row");repository.dao.putTombstone(TombstoneEntity(purged,1))
  assertEquals(2,repository.dao.noteCount().first())
  val changed=async {withTimeout(3000) {repository.dao.noteCount().first {it==1}}}
  repository.dao.put(repository.dao.get(live)!!.copy(archived=true));assertEquals(1,changed.await())
 }
 @Test fun readingHierarchyLeavesPendingPayloadDraftAndRevisionIdentityUntouched()=runBlocking {
  val id=repository.create("Synthetic local #Project/Spunto")
  repository.dao.putDraft(DraftEntity("capture","Synthetic retained draft",42))
  val noteBefore=repository.dao.get(id);val pendingBefore=repository.dao.outbox();val historyBefore=repository.dao.history(id).first()
  repeat(3) {assertEquals(listOf(1,1),repository.dao.labels().first().map {row ->row.count})}
  assertEquals(noteBefore,repository.dao.get(id));assertEquals(pendingBefore,repository.dao.outbox());assertEquals(historyBefore,repository.dao.history(id).first())
  assertEquals(DraftEntity("capture","Synthetic retained draft",42),repository.dao.draft("capture").first())
 }
}
