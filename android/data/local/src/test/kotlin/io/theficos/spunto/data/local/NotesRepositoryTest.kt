package io.theficos.spunto.data.local
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.theficos.spunto.core.model.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class) @Config(sdk=[28])
class NotesRepositoryTest {
 @get:org.junit.Rule val timeout = org.junit.rules.Timeout(10, java.util.concurrent.TimeUnit.MINUTES)
 private lateinit var db:SpuntoDatabase
 private lateinit var repo:NotesRepository
 @Before fun setup() { db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(),SpuntoDatabase::class.java).allowMainThreadQueries().build(); repo=NotesRepository(db) }
 @After fun close() { db.close() }
 @Test fun savesAndCompactsTransactionallyAndSearchesUnicode61() = runBlocking {
  val id=repo.create("Perché dall’Augustarello #Idea")
  repo.update(id,"Perché dall’Augustarello #idea #work")
  assertEquals(1,repo.dao.outbox().size)
  assertEquals("note.create",Json.decodeFromString<OutboxOperation>(repo.dao.outbox().single().payload).type)
  assertEquals(id,repo.search("augustarello").single().id)
  assertEquals(id,repo.search("perche").single().id)
  assertEquals(id,repo.search("aug").single().id)
  assertEquals(listOf("idea","work"),repo.labels().first().map { it.key })
  assertEquals(1,repo.history(id).first().size)
 }
 @Test fun inlineLabelSearchIncludesDescendantsAndExcludesPlainWords() = runBlocking { val id=repo.create("labelled #work/project");repo.create("plain work/project");assertEquals(listOf(id),repo.search("#work").map { it.id });assertEquals(listOf(id),repo.search("labelled #work").map { it.id }) }
 @Test fun offlineSuggestionsPreserveAllWordsAndMatchServerPlacement() = runBlocking {
  val id=repo.create("Original first line\nOriginal second line #old")
  repo.dao.outbox().forEach { repo.dao.removeOutbox(it.opId) }
  repo.dao.putOutput(PluginOutputEntity("suggestion","title",id,"""{"action":"set_title","payload":{"title":"A title"},"status":"pending"}"""));repo.decideSuggestion("title","accept")
  assertEquals("# A title\n\nOriginal first line\nOriginal second line #old",repo.dao.get(id)!!.body)
  repo.dao.putOutput(PluginOutputEntity("suggestion","label",id,"""{"action":"add_label","payload":{"path":"new"},"status":"pending"}"""));repo.decideSuggestion("label","accept")
  assertTrue(repo.dao.get(id)!!.body.endsWith("#old #new"))
  repo.dao.putOutput(PluginOutputEntity("suggestion","append",id,"""{"action":"append_text","payload":{"text":"Added words"},"status":"pending"}"""));repo.decideSuggestion("append","accept")
  assertTrue(repo.dao.get(id)!!.body.endsWith("\n\nAdded words"));assertEquals(3,repo.dao.outbox().size)
 }
 @Test fun latestAgentActorPersistsAndOwnerWritesClearIt()=runBlocking { val id=uuidV7();repo.putNote(Note(id,"owner originally",serverRev=2,updatedViaAgent=true));assertTrue(repo.dao.get(id)!!.note().updatedViaAgent);repo.update(id,"owner edits");assertFalse(repo.dao.get(id)!!.updatedViaAgent);repo.putNote(repo.dao.get(id)!!.note().copy(updatedViaAgent=true));repo.setFlags(id,pinned=true);assertFalse(repo.dao.get(id)!!.updatedViaAgent) }
 @Test fun editsNeverMutateSentOperation() = runBlocking {
  val id=repo.create("first"); val sent=repo.dao.outbox().single();repo.dao.markSent(listOf(sent.opId));repo.update(id,"second")
  val ops=repo.dao.outbox();assertEquals(2,ops.size);assertEquals(sent.payload,ops.first().payload);assertEquals("second",repo.dao.get(id)!!.body)
 }
 @Test fun draftAcknowledgementPreservesTyping() = runBlocking { repo.saveDraft(body="old");repo.saveDraft(body="new");repo.clearDraft("capture","old");assertEquals("new",repo.draft().first()!!.body) }
 @Test fun rejectedOperationStaysLocal() = runBlocking { val id=repo.create("local");val op=repo.dao.outbox().single();repo.dao.reject(op.opId,"not_found");repo.update(id,"new words");assertEquals(2,repo.dao.outbox().size);assertEquals("new words",repo.dao.get(id)!!.body) }
 @Test fun localEditBurstKeepsItsFirstAncestorAndBreaksAfterTimeoutOrRemoteChange()=runBlocking {
  val id=repo.create("Initial owner words")
  repo.update(id,"First edit");repo.update(id,"Second edit")
  assertEquals(listOf("Initial owner words"),repo.history(id).first().map { it.body })
  val burst=repo.dao.latestRevision(id)!!;repo.dao.touchRevision(burst.id,System.currentTimeMillis()-600_001)
  repo.update(id,"After ten minutes")
  assertEquals(2,repo.history(id).first().size)
  repo.putNote(repo.dao.get(id)!!.note().copy(body="Other actor words",serverRev=3,pending=false))
  repo.update(id,"Owner after remote edit")
  assertEquals(4,repo.history(id).first().size)
  assertTrue(repo.history(id).first().any { it.body=="Other actor words" })
 }
 @Test fun canonicalCacheOwnsCoalescedHistoryWithoutHidingUnacknowledgedTyping()=runBlocking {
  val id=uuidV7();val server="https://history.example"
  repo.dao.putState(SyncStateEntity(epoch="e",cursor="e.9",serverUrl=server))
  repo.putNote(Note(id,"Current text",serverRev=5))
  repo.dao.putRevision(RevisionEntity(noteId=id,body="Intermediate autosave",at=100,version=2,historySource="replica"))
  repo.dao.putServerRevisions(listOf(ServerRevisionEntity(server,"e",id,1,"Original text",1,"device-a","create"),ServerRevisionEntity(server,"e",id,5,"Current text",5,"device-a","edit")))
  repo.dao.putHistoryCache(HistoryCacheEntity(server,"e",id,10,5))
  assertEquals(listOf(5,1),repo.history(id).first().map { it.version })
  repo.update(id,"Offline latest typing")
  assertTrue(repo.history(id).first().any { it.body=="Current text" })
  assertEquals("Offline latest typing",repo.dao.get(id)!!.body)
  assertEquals(1,repo.dao.outbox().size)
  repo.dao.putState(SyncStateEntity(epoch="restored",cursor="restored.0",serverUrl=server))
  assertTrue(repo.history(id).first().none { it.historySource=="server" })
 }

 @Test fun canonicalHydrationNeverHidesAnOfflineCreateAncestorOrMigratedUnknownHistory()=runBlocking {
  val id=repo.create("First locally saved words");repo.update(id,"Latest locally saved words")
  val url="https://history.example";repo.dao.putState(SyncStateEntity(epoch="e",serverUrl=url))
  repo.dao.outbox().forEach { repo.dao.removeOutbox(it.opId) }
  repo.putNote(repo.dao.get(id)!!.note().copy(serverRev=1,pending=false))
  repo.dao.putRevision(RevisionEntity(noteId=id,body="Migrated owner words",at=1,version=1))
  repo.dao.putServerRevisions(listOf(ServerRevisionEntity(url,"e",id,1,"Latest locally saved words",2,"phone","create")))
  repo.dao.putHistoryCache(HistoryCacheEntity(url,"e",id,3,1))
  val bodies=repo.history(id).first().map { it.body }
  assertTrue("First offline save remains restorable",bodies.contains("First locally saved words"));assertTrue(bodies.contains("Migrated owner words"));assertTrue(bodies.contains("Latest locally saved words"))
 }

 @Test fun restoringCachedHistoryIsAnOrdinaryOfflineBodyEditWithProtectedAncestor()=runBlocking {
  val id=uuidV7();repo.putNote(Note(id,"Current owner words",serverRev=5,privateNote=true,pinned=true,pinnedAt=10,privateAt=11))
  val revision=RevisionEntity(id="server/e/1",noteId=id,body="Earlier owner words",at=1,version=1,historySource="server",changeKind="create")
  repo.restoreHistory(id,revision)
  val restored=repo.dao.get(id)!!;assertEquals("Earlier owner words",restored.body);assertTrue(restored.privateNote);assertTrue(restored.pinned);assertEquals(10L,restored.pinnedAt);assertEquals(11L,restored.privateAt)
  val op=Json.decodeFromString<OutboxOperation>(repo.dao.outbox().single().payload);assertEquals("note.update",op.type);assertEquals(5,op.baseVersion);assertEquals("Earlier owner words",op.body)
  assertEquals("restore",repo.dao.latestRevision(id)!!.changeKind)
  repo.update(id,"Edit after restore");assertTrue(repo.history(id).first().any { it.body=="Earlier owner words" })
  assertTrue(runCatching {repo.restoreHistory(uuidV7(),revision)}.isFailure)
  val missing=uuidV7();assertTrue(runCatching {repo.restoreHistory(missing,revision.copy(noteId=missing))}.isFailure);assertNull(repo.dao.get(missing))
  val pending=repo.dao.outbox();repo.dao.putTombstone(TombstoneEntity(id,12));assertTrue(runCatching {repo.restoreHistory(id,revision)}.isFailure);assertEquals(pending,repo.dao.outbox());assertEquals("Edit after restore",repo.dao.get(id)!!.body)
 }

 @Test fun delayedEditorDisposalCannotOvertakeRestoreOrNewTyping()=runBlocking {
  val id=uuidV7();repo.putNote(Note(id,"A",serverRev=1,privateNote=true))
  val editor=repo.beginEditor(id);assertTrue(repo.persistEditor(repo.editorWrite(editor,"B"),commit=true))
  val disposal=repo.editorWrite(editor,"B")
  val gate=kotlinx.coroutines.CompletableDeferred<Unit>()
  val delayed=async {gate.await();repo.finishEditor(disposal)}
  repo.restoreHistory(id,RevisionEntity(noteId=id,body="A",at=1,version=1))
  assertNull(repo.dao.draftSnapshot(id));assertEquals("A",repo.dao.get(id)!!.body)
  val newEditor=repo.beginEditor(id);assertTrue(repo.persistEditor(repo.editorWrite(newEditor,"D"),commit=true))
  val before=repo.dao.outbox();gate.complete(Unit);assertFalse(delayed.await())
  assertEquals(before,repo.dao.outbox());assertEquals("D",repo.dao.get(id)!!.body);assertEquals("D",repo.dao.draftSnapshot(id)!!.body)
  assertTrue(repo.dao.get(id)!!.privateNote)
 }
 @Test fun readAwaitsCommitAndOldEditorCallbacksAreFenced()=runBlocking {
  val id=uuidV7();repo.putNote(Note(id,"A",serverRev=1))
  val editor=repo.beginEditor(id);val stale=repo.editorWrite(editor,"Older partial")
  assertTrue(repo.finishEditor(repo.editorWrite(editor,"B")))
  assertEquals("B",repo.dao.get(id)!!.body);assertFalse(repo.persistEditor(stale,commit=true))
  repo.restoreHistory(id,RevisionEntity(noteId=id,body="A",at=1,version=1))
  assertEquals("A",repo.dao.get(id)!!.body);assertNull(repo.dao.draftSnapshot(id))
 }
 @Test fun editorWriteOrderAndExactDraftAcknowledgementPreserveNewerText()=runBlocking {
  val id=uuidV7();repo.putNote(Note(id,"B",serverRev=2))
  val editor=repo.beginEditor(id);val old=repo.editorWrite(editor,"Old")
  assertTrue(repo.persistEditor(repo.editorWrite(editor,"New typing")))
  assertFalse(repo.persistEditor(old,commit=true));assertEquals("B",repo.dao.get(id)!!.body)
  repo.restoreHistory(id,RevisionEntity(noteId=id,body="A",at=1,version=1))
  assertEquals("New typing",repo.dao.draftSnapshot(id)!!.body)
  // Even an equal-body acknowledgement must not remove a newer timestamp.
  repo.dao.putDraft(DraftEntity(id,"same body",20));repo.dao.clearDraftSnapshot(id,"same body",19)
  assertEquals(20L,repo.dao.draftSnapshot(id)!!.updatedAt)
 }
 @Test fun explicitRestoreAncestorSurvivesAcknowledgementCanonicalCoalescingAndLaterAutosave()=runBlocking {
  val id=uuidV7();val url="https://history.example";repo.dao.putState(SyncStateEntity(epoch="e",serverUrl=url))
  repo.putNote(Note(id,"B",serverRev=2))
  repo.restoreHistory(id,RevisionEntity(noteId=id,body="A",at=1,version=1,historySource="server"))
  repo.dao.outbox().forEach {repo.dao.removeOutbox(it.opId)}
  repo.putNote(repo.dao.get(id)!!.note().copy(serverRev=3,pending=false))
  repo.dao.putServerRevisions(listOf(ServerRevisionEntity(url,"e",id,3,"A",3,"phone","edit"),ServerRevisionEntity(url,"e",id,1,"A",1,"phone","create")))
  repo.dao.putHistoryCache(HistoryCacheEntity(url,"e",id,4,3))
  assertTrue(repo.history(id).first().any {it.body=="B" && it.changeKind=="restore" && it.historySource=="local"})
  repo.update(id,"D");repo.dao.outbox().forEach {repo.dao.removeOutbox(it.opId)}
  repo.putNote(repo.dao.get(id)!!.note().copy(serverRev=4,pending=false))
  repo.dao.clearServerRevisions(url,"e",id)
  repo.dao.putServerRevisions(listOf(ServerRevisionEntity(url,"e",id,4,"D",4,"phone","edit"),ServerRevisionEntity(url,"e",id,1,"A",1,"phone","create")))
  repo.dao.putHistoryCache(HistoryCacheEntity(url,"e",id,5,4))
  assertTrue(repo.history(id).first().any {it.body=="B" && it.changeKind=="restore"})
 }

 private suspend fun suggestion(id:String,noteId:String?,action:String,payload:JsonObject,version:JsonElement?=null) {
  val data=buildJsonObject {put("action",action);put("payload",payload);put("status","pending");version?.let {put("note_version",it)}}
  repo.dao.putOutput(PluginOutputEntity("suggestion",id,noteId,data.toString()))
 }
 private suspend fun suggestionStatus(id:String)=Json.parseToJsonElement(repo.dao.output("suggestion",id)!!.payload).jsonObject.getValue("status").jsonPrimitive.content
 @Test fun replacementRejectsInvalidBasisWithoutChangingWordsOrQueuingAnOperation()=runBlocking {
  for(mode in listOf("stale","missing_version","quoted_version","missing_note","private","trashed","tombstoned","pending_edit")) {
   val id=uuidV7();val proposal=uuidV7()
   if(mode!="missing_note") repo.putNote(Note(id,"Owner words",serverRev=3,privateNote=mode=="private",trashed=mode=="trashed"))
   if(mode=="tombstoned") repo.dao.putTombstone(TombstoneEntity(id,12))
   if(mode=="pending_edit") repo.update(id,"Newer offline owner words")
   val version=when(mode) {"missing_version"->null;"quoted_version"->JsonPrimitive("3");"stale"->JsonPrimitive(2);else->JsonPrimitive(3)}
   suggestion(proposal,id,"replace_body",buildJsonObject {put("body","Model replacement")},version)
   val notes=repo.dao.all();val outbox=repo.dao.outbox();val history=repo.dao.history(id).first()
   val failure=runCatching {repo.decideSuggestion(proposal,"accept")}.exceptionOrNull()
   assertTrue(mode,failure is IllegalArgumentException);assertTrue(failure!!.message.orEmpty().contains("superseded"))
   assertEquals(mode,notes,repo.dao.all());assertEquals(mode,outbox,repo.dao.outbox());assertEquals(mode,history,repo.dao.history(id).first())
   assertEquals("superseded",suggestionStatus(proposal));if(mode=="missing_note") assertNull(repo.dao.get(id))
  }
 }
 @Test fun validReplacementAppliesOnceAndExplicitDismissDoesNotDependOnTargetEligibility()=runBlocking {
  val id=uuidV7();repo.putNote(Note(id,"Old machine words",serverRev=3,machine=true,sourceKind="plugin",origin="plugin"))
  suggestion("replace",id,"replace_body",buildJsonObject {put("body","Owner accepted words")},JsonPrimitive(3))
  repo.decideSuggestion("replace","accept");assertEquals("Owner accepted words",repo.dao.get(id)!!.body);assertFalse(repo.dao.get(id)!!.machine);assertEquals("accepted",suggestionStatus("replace"))
  val accepted=Json.decodeFromString<OutboxOperation>(repo.dao.outbox().single().payload);assertEquals("suggestion.decide",accepted.type);assertEquals("accept",accepted.decision)
  val missing=uuidV7();suggestion("dismiss",missing,"replace_body",buildJsonObject {put("body","Unused")},JsonPrimitive(1))
  repo.decideSuggestion("dismiss","dismiss");assertNull(repo.dao.get(missing));assertEquals("dismissed",suggestionStatus("dismiss"))
  assertTrue(repo.dao.outbox().map {Json.decodeFromString<OutboxOperation>(it.payload)}.any {it.suggestionId=="dismiss" && it.decision=="dismiss"})
 }
 @Test fun labelAcceptRequiresWebImpactReviewForRenameAndGardenerClusterButDismissStillQueues()=runBlocking {
  val id=uuidV7();repo.putNote(Note(id,"Owner #old",serverRev=1));val other=uuidV7();repo.putNote(Note(other,"Unlabelled private words",serverRev=1,privateNote=true))
  val payloads=listOf(buildJsonObject {put("old","old");put("new","new")},buildJsonObject {put("new","cluster");put("note_ids",buildJsonArray {add(id);add(other)});put("note_versions",buildJsonObject {put(id,1);put(other,1)})})
  for((index,payload) in payloads.withIndex()) {
   val proposal="label-"+index;suggestion(proposal,null,"label_change",payload)
   val notes=repo.dao.all();val outbox=repo.dao.outbox()
   val failure=runCatching {repo.decideSuggestion(proposal,"accept")}.exceptionOrNull()
   assertTrue(failure is IllegalArgumentException);assertTrue(failure!!.message.orEmpty().contains("web Review"))
   assertEquals(notes,repo.dao.all());assertEquals(outbox,repo.dao.outbox());assertEquals("pending",suggestionStatus(proposal))
   repo.decideSuggestion(proposal,"dismiss");assertEquals("dismissed",suggestionStatus(proposal))
  }
 }
 @Test fun mergeValidatesBothReferencesBeforeAnyBodyOrTrashMutation()=runBlocking {
  for(mode in listOf("missing_source","missing_target","private_source","private_target","trashed_source","trashed_target","tombstoned_source","tombstoned_target","same_note","stale_source","stale_target","pending_source")) {
   val source=uuidV7();val target=if(mode=="same_note") source else uuidV7();val proposal=uuidV7()
   if(mode!="missing_source") repo.putNote(Note(source,"Source owner words",serverRev=2,privateNote=mode=="private_source",trashed=mode=="trashed_source"))
   if(mode!="missing_target" && mode!="same_note") repo.putNote(Note(target,"Target owner words",serverRev=3,privateNote=mode=="private_target",trashed=mode=="trashed_target"))
   if(mode=="tombstoned_source") repo.dao.putTombstone(TombstoneEntity(source,12))
   if(mode=="tombstoned_target") repo.dao.putTombstone(TombstoneEntity(target,13))
   if(mode=="pending_source") repo.update(source,"Newer offline source")
   suggestion(proposal,target,"merge_notes",buildJsonObject {put("source_id",source);put("target_id",target);put("source_version",if(mode=="stale_source") 1 else 2);put("target_version",if(mode=="stale_target") 2 else 3)})
   val notes=repo.dao.all();val outbox=repo.dao.outbox()
   assertTrue(mode,runCatching {repo.decideSuggestion(proposal,"accept")}.exceptionOrNull() is IllegalArgumentException)
   assertEquals(mode,notes,repo.dao.all());assertEquals(mode,outbox,repo.dao.outbox());assertEquals("superseded",suggestionStatus(proposal))
  }
 }
 @Test fun validMergeAtomicallyUpdatesTargetTrashesSourceAndCapturesBothPullGuards()=runBlocking {
  val source=uuidV7();val target=uuidV7();repo.putNote(Note(source,"Source owner words",serverRev=2));repo.putNote(Note(target,"Target owner words",serverRev=3))
  suggestion("merge",target,"merge_notes",buildJsonObject {put("source_id",source);put("target_id",target);put("source_version",2);put("target_version",3)})
  repo.decideSuggestion("merge","accept")
  assertEquals("Target owner words\n\nSource owner words",repo.dao.get(target)!!.body);assertFalse(repo.dao.get(target)!!.trashed);assertTrue(repo.dao.get(source)!!.trashed)
  assertTrue(repo.dao.get(source)!!.trashedAt>0);assertEquals("accepted",suggestionStatus("merge"))
  val operation=Json.decodeFromString<OutboxOperation>(repo.dao.outbox().single().payload)
  assertEquals(setOf(source,target),operation.affectedNoteIds.toSet());assertEquals("suggestion.decide",operation.type)
 }

 @Test fun finishingMissingOrTombstonedNotePreservesLatestDraftWithoutResurrection()=runBlocking {
  for(purged in listOf(false,true)) {
   val id=uuidV7();repo.putNote(Note(id,"Previously committed",privateNote=true));repo.saveDraft(id,"Earlier draft")
   val editor=repo.beginEditor(id);val write=repo.editorWrite(editor,"Latest uncommitted owner words")
   if(purged) repo.dao.putTombstone(TombstoneEntity(id,12)) else repo.dao.remove(id)
   val notes=repo.dao.all();val outbox=repo.dao.outbox()
   val failure=runCatching {repo.finishEditor(write)}.exceptionOrNull()
   assertTrue(failure is IllegalArgumentException);assertEquals("Latest uncommitted owner words",repo.dao.draftSnapshot(id)!!.body)
   assertEquals(notes,repo.dao.all());assertEquals(outbox,repo.dao.outbox());if(!purged) assertNull(repo.dao.get(id))
   repo.abandonEditor(editor)
  }
 }

 @Test fun repeatedAcceptIsAnIdempotentNoOpEvenForAppendActions()=runBlocking {
  val id=uuidV7();repo.putNote(Note(id,"Original owner words",serverRev=1))
  suggestion("repeat-append",id,"append_text",buildJsonObject {put("text","Appended exactly once")})
  var changes=0;val tracked=NotesRepository(db) {changes++}
  tracked.decideSuggestion("repeat-append","accept")
  val notes=repo.dao.all();val history=repo.dao.history(id).first();val outbox=repo.dao.outbox();val cached=repo.dao.output("suggestion","repeat-append")
  tracked.decideSuggestion("repeat-append","accept");assertEquals(1,changes)
  assertEquals("Original owner words\n\nAppended exactly once",repo.dao.get(id)!!.body)
  assertEquals(notes,repo.dao.all());assertEquals(history,repo.dao.history(id).first());assertEquals(outbox,repo.dao.outbox());assertEquals(cached,repo.dao.output("suggestion","repeat-append"))
 }
 @Test fun inactiveAcceptCallbacksNeverAlterNotesHistoryCachedStatusOrOutbox()=runBlocking {
  val id=uuidV7();repo.putNote(Note(id,"Owner words remain",serverRev=2));var changes=0;val tracked=NotesRepository(db) {changes++}
  for(status in listOf("expired","superseded","dismissed")) for(action in listOf("append_text","set_title")) {
   val proposal=status+"-"+action
   val data=buildJsonObject {put("status",status);put("action",action);put("payload",buildJsonObject {put("text","Must not append");put("title","Must not prefix")})}
   repo.dao.putOutput(PluginOutputEntity("suggestion",proposal,id,data.toString()))
   val notes=repo.dao.all();val history=repo.dao.history(id).first();val outbox=repo.dao.outbox();val cached=repo.dao.output("suggestion",proposal)
   val failure=runCatching {tracked.decideSuggestion(proposal,"accept")}.exceptionOrNull()
   assertEquals(0,changes)
   assertTrue(status,failure is IllegalArgumentException);assertTrue(failure!!.message.orEmpty().contains("no longer pending"))
   assertEquals(notes,repo.dao.all());assertEquals(history,repo.dao.history(id).first());assertEquals(outbox,repo.dao.outbox());assertEquals(cached,repo.dao.output("suggestion",proposal))
  }
 }

}
