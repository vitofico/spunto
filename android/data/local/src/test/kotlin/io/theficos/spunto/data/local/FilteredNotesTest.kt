package io.theficos.spunto.data.local

import androidx.room.Room
import androidx.paging.PagingSource
import androidx.test.core.app.ApplicationProvider
import io.theficos.spunto.core.model.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.ZoneId
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class) @Config(sdk=[28])
class FilteredNotesTest {
 @get:Rule val timeout=org.junit.rules.Timeout(10,java.util.concurrent.TimeUnit.MINUTES)
 private lateinit var db:SpuntoDatabase
 private lateinit var repository:NotesRepository
 @Before fun setup() { db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(),SpuntoDatabase::class.java).allowMainThreadQueries().build();repository=NotesRepository(db) }
 @After fun close() { db.close() }
 private suspend fun rows(spec:TimelineSpec)=repository.dao.filteredNotes(timelineQuery(spec)).map { it.note() }
 @Test fun sharedDeclarativeViewFixtureRunsAgainstRealRoom()=runBlocking {
  val fixture=Json.parseToJsonElement(File(System.getProperty("spunto.fixtures")?:"/spec/fixtures","views.json").readText()).jsonObject
  val now=fixture.getValue("now").jsonPrimitive.long;val plugin=fixture.getValue("plugin_id").jsonPrimitive.content
  val ids=mutableMapOf<String,String>()
  fixture.getValue("notes").jsonArray.forEach { item -> val data=item.jsonObject;val key=data.getValue("key").jsonPrimitive.content;val id=uuidV7();ids[id]=key
   fun text(name:String)=data[name]?.jsonPrimitive?.contentOrNull.orEmpty()
   fun flag(name:String)=data[name]?.jsonPrimitive?.booleanOrNull?:false
   repository.putNote(Note(id,text("body"),createdAt=now-data.getValue("age_days").jsonPrimitive.long*86400000,pinned=flag("pinned"),archived=flag("archived"),sourceKind=text("source").ifEmpty { "owner" },sourceRef=text("source_ref").takeIf { it.isNotEmpty() },updatedViaAgent=flag("latest_agent")))
   (data["members"] as? JsonArray)?.forEach { member -> repository.dao.putOutput(PluginOutputEntity("annotation",id+"/"+member.jsonPrimitive.content,id,buildJsonObject {put("plugin_id",plugin);put("kind","member:"+member.jsonPrimitive.content)}.toString())) }
  }
  fixture.getValue("cases").jsonArray.forEach { case -> val data=case.jsonObject
   val expected=data.getValue("expected").jsonArray.map { it.jsonPrimitive.content }.sorted()
   val actual=rows(TimelineSpec(kind=TimelineKind.PLUGIN,pluginId=plugin,filter=data.getValue("filter").jsonObject,now=now)).map { ids.getValue(it.id) }.sorted()
   assertEquals(data.getValue("filter").toString(),expected,actual)
  }
 }
 @Test fun homeArchiveTrashAndNestedLabelsUseTheirOwnStableOrder()=runBlocking {
  suspend fun note(body:String,at:Long,pinned:Boolean=false,archived:Boolean=false,trashed:Boolean=false,trashAt:Long=0):String {val id=uuidV7();repository.putNote(Note(id,body,createdAt=at,pinned=pinned,archived=archived,trashed=trashed,trashedAt=trashAt));return id}
  val pinned=note("Pinned #project/spunto",1,true);val newest=note("Newest #project",9);val archivedPinned=note("Old archive #project/old",2,true,true);val archivedNew=note("New archive",8,false,true)
  val oldTrash=note("Newer creation but earlier trash",10,true,trashed=true,trashAt=20);val newTrash=note("Older creation but latest trash",1,false,trashed=true,trashAt=30)
  assertEquals(listOf(pinned,newest),rows(TimelineSpec()).map { it.id })
  assertEquals(listOf(archivedNew,archivedPinned),rows(TimelineSpec(kind=TimelineKind.ARCHIVE)).map { it.id })
  assertEquals(listOf(newTrash,oldTrash),rows(TimelineSpec(kind=TimelineKind.TRASH)).map { it.id })
  assertEquals(listOf(archivedPinned,pinned,newest),rows(TimelineSpec(kind=TimelineKind.LABEL,label="project")).map { it.id })
 }
 @Test fun createdRangeUsesInclusiveFromAndWholeInclusiveToWithoutSilentInvalidDates()=runBlocking {
  val range=CreatedRange.parse("2026-10-14","2026-10-14",ZoneOffset.UTC)
  val before=uuidV7();val start=uuidV7();val end=uuidV7();val after=uuidV7()
  for((id,at) in listOf(before to range.fromInclusive!!-1,start to range.fromInclusive!!,end to range.beforeExclusive!!-1,after to range.beforeExclusive!!)) repository.putNote(Note(id,id,createdAt=at))
  assertEquals(listOf(end,start),rows(TimelineSpec(created=range)).map { it.id })
  assertTrue(runCatching {CreatedRange.parse("2026-02-30","")}.isFailure);assertTrue(runCatching {CreatedRange.parse("2026-10-15","2026-10-14")}.isFailure)
  assertTrue(rows(TimelineSpec(valid=false)).isEmpty())
  val dst=CreatedRange.parse("2026-03-29","2026-03-29",ZoneId.of("Europe/Madrid"));assertEquals(23*60*60*1000L,dst.beforeExclusive!!-dst.fromInclusive!!)
 }
 @Test fun closedFiltersRejectInvalidTypesAndNeverInterpolateValues()=runBlocking {
  val id=uuidV7();repository.putNote(Note(id,"#safe",sourceKind="agent"))
  for(filter in listOf(buildJsonObject {put("pinned","true")},buildJsonObject {put("days","2")},buildJsonObject {put("unknown",true)},buildJsonObject {put("source","agent' OR 1=1--")},buildJsonObject {put("labels",buildJsonArray {add("safe' OR 1=1--")})})) assertTrue(rows(TimelineSpec(kind=TimelineKind.PLUGIN,pluginId="fixture",filter=filter)).isEmpty())
  assertEquals(id,rows(TimelineSpec(kind=TimelineKind.PLUGIN,pluginId="fixture",filter=buildJsonObject {put("source","agent")})).single().id)
 }
 @Test fun pagingLoadsBoundedPagesAndMembershipUpdatesInvalidateItsSource()=runBlocking {
  repeat(90) { index -> repository.putNote(Note(uuidV7(),"Paged owner note",createdAt=index.toLong())) }
  val source=repository.dao.filteredPaging(timelineQuery(TimelineSpec()))
  val first=source.load(PagingSource.LoadParams.Refresh<Int>(null,40,false)) as PagingSource.LoadResult.Page<Int,NoteEntity>
  assertEquals(40,first.data.size);assertNotNull(first.nextKey)
  val second=source.load(PagingSource.LoadParams.Append(first.nextKey!!,40,false)) as PagingSource.LoadResult.Page<Int,NoteEntity>
  assertEquals(40,second.data.size);assertTrue(first.data.map {it.id}.intersect(second.data.map {it.id}.toSet()).isEmpty())
  val id=first.data.first().id;val spec=TimelineSpec(kind=TimelineKind.PLUGIN,pluginId="example",filter=buildJsonObject {put("member","reviewed")})
  val members=repository.dao.filteredPaging(timelineQuery(spec));val invalid=CompletableDeferred<Unit>();members.registerInvalidatedCallback { invalid.complete(Unit) }
  assertTrue((members.load(PagingSource.LoadParams.Refresh<Int>(null,40,false)) as PagingSource.LoadResult.Page<Int,NoteEntity>).data.isEmpty())
  repository.dao.putOutput(PluginOutputEntity("annotation","member",id,"{\"plugin\":\"example\",\"kind\":\"member:reviewed\"}"));withTimeout(5000) {invalid.await()};assertEquals(id,rows(spec).single().id)
  repository.dao.putOutput(PluginOutputEntity("annotation","member",id,"{\"plugin\":\"example\",\"kind\":\"panel\"}"));assertTrue(rows(spec).isEmpty())
  repository.dao.putOutput(PluginOutputEntity("annotation","member",id,"{\"plugin\":\"example\",\"kind\":\"member:reviewed\"}"));repository.dao.removeOutput("annotation","member");assertTrue(rows(spec).isEmpty())
 }
 @Test fun derivedTasksExcludeCodeAndFollowOwnerEdits()=runBlocking {
  val fence="```";val body="- [ ] real\n"+fence+"\n- [ ] code\n"+fence+"\n\n[ ] paragraph\n\n## [ ] heading\n\n`[ ] inline`"
  assertEquals(3,openTaskCount(body))
  val id=uuidV7();repository.putNote(Note(id,body));assertEquals(3,repository.dao.get(id)!!.openTasks)
  val tasks=TimelineSpec(kind=TimelineKind.PLUGIN,pluginId="tasks",filter=buildJsonObject {put("open_tasks",true)})
  assertEquals(id,rows(tasks).single().id);repository.update(id,"- [x] completed");assertTrue(rows(tasks).isEmpty())
 }
 @Test fun booleanFiltersRequireActualBooleansForBothMatchingValues()=runBlocking {
  val yes=uuidV7();val no=uuidV7()
  repository.putNote(Note(yes,"- [ ] open",pinned=true,archived=true))
  repository.putNote(Note(no,"- [x] done",pinned=false,archived=false))
  for(field in listOf("open_tasks","pinned","archived")) {
   for((active,expected) in listOf(true to yes,false to no)) {
    val valid=buildJsonObject {put(field,active)}
    assertEquals(field+"="+active,listOf(expected),rows(TimelineSpec(kind=TimelineKind.PLUGIN,pluginId="fixture",filter=valid)).map {it.id})
    val invalid=buildJsonObject {put(field,active.toString())}
    assertTrue(field+" must reject quoted "+active,rows(TimelineSpec(kind=TimelineKind.PLUGIN,pluginId="fixture",filter=invalid)).isEmpty())
   }
  }
 }

}
