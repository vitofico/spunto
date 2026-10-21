package io.theficos.spunto.scale.fixture

import io.theficos.spunto.core.model.*
import io.theficos.spunto.data.local.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ln
import kotlin.math.max

enum class ScaleCorrectnessPhase { PAYLOAD, HIERARCHY, FOLDING, INDEX, QUERY, TIMELINES, COMPLETE }

/** Independent membership oracle restricted to this corpus's folded ASCII letters/digits. */
object ProductScaleCorrectness {
 private fun tokens(raw:String):List<String> {
  val value=fold(raw)
  require(value.codePoints().noneMatch {it>127 && (Character.isLetter(it) || Character.isDigit(it))}) {"The corpus outgrew this limited tokenizer oracle."}
  return Regex("[a-z0-9]+").findAll(value).map {it.value}.toList()
 }
 private data class Indexed(val note:Note,val fields:List<List<String>>,val keys:List<String>)
 private fun index(fixture:ProductScaleFixtures.Fixture)=fixture.notes.map {Indexed(it,listOf(tokens(it.title),tokens(it.body)),labels(it.body).map {label ->label.key})}
 fun expectedMembers(fixture:ProductScaleFixtures.Fixture,query:String):List<Note> = members(index(fixture),query)
 private fun members(indexed:List<Indexed>,query:String):List<Note> {
  val parsed=searchQuery(query)
  if(parsed.expression==null && parsed.labelKeys.isEmpty()) return emptyList()
  val phrases=parsed.expression?.let {expression ->Regex("\"([^\"]+)\"").findAll(expression).map {it.groupValues[1]}.toList()}.orEmpty()
  fun contains(words:List<String>,phrase:String):Boolean {
   val prefix=phrase.endsWith('*');val terms=phrase.removeSuffix("*").split(' ')
   return words.indices.any {start ->start+terms.size<=words.size && terms.indices.all {index ->if(prefix && index==terms.lastIndex) words[start+index].startsWith(terms[index]) else words[start+index]==terms[index]}}
  }
  return indexed.filter {entry ->
   if(entry.note.trashed) false else {
    parsed.labelKeys.all {key ->entry.keys.any {it==key || it.startsWith("$key/")}} && phrases.all {phrase ->entry.fields.any {contains(it,phrase)}}
   }
  }.map {it.note}
 }
 /** Separate score calculation from FTS's actual matchinfo, with explicit approved 2/1 weights. */
 private fun referenceScore(bytes:ByteArray):Double {
  val ints=ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder()).asIntBuffer()
  val phraseCount=ints[0];val columnCount=ints[1];val documentCount=ints[2];require(columnCount==2)
  var sum=0.0
  repeat(phraseCount) {phrase ->repeat(columnCount) {column ->
   val offset=3+columnCount*2+(phrase*columnCount+column)*3
   val frequency=ints[offset].toDouble()
   if(frequency>0) {
    val documentFrequency=ints[offset+2].toDouble()
    val idf=ln(1+(documentCount-documentFrequency+.5)/(documentFrequency+.5))
    val lengthRatio=ints[3+columnCount+column].toDouble()/max(1,ints[3+column])
    sum+=(if(column==0) 2.0 else 1.0)*idf*frequency*(1.2+1)/(frequency+1.2*((1-.75)+.75*lengthRatio))
   }
  }}
  return sum
 }
 suspend fun verify(repository:NotesRepository,fixture:ProductScaleFixtures.Fixture,onProgress:(ScaleCorrectnessPhase,Int,Int)->Unit={_,_,_->}):JsonObject {
  val all=repository.dao.all();require(all.size==10_000 && all.all {!it.pending})
  require(repository.dao.outbox().isEmpty())
  require(all.associate {it.id to it.note()}==fixture.notes.associateBy {it.id}) {"Stored product Note payload differs from deterministic fixture."}
  onProgress(ScaleCorrectnessPhase.PAYLOAD,10_000,10_000)
  val expectedLabels=mutableMapOf<String,MutableSet<String>>()
  fixture.notes.filter {!it.trashed && !it.archived}.forEach {note ->labels(note.body).forEach {label ->val parts=label.key.split('/');parts.indices.forEach {depth ->expectedLabels.getOrPut(parts.take(depth+1).joinToString("/")) {mutableSetOf()}.add(note.id)}}}
  require(repository.dao.labels().first().associate {it.key to it.count}==expectedLabels.mapValues {it.value.size}) {"Hierarchy must count distinct note IDs."}
  onProgress(ScaleCorrectnessPhase.HIERARCHY,expectedLabels.size,expectedLabels.size)
  val probes=listOf("perche" to 1,"citta" to 2,"augustarello" to 3,"strasse" to 4,"finestra" to 5,"CAFFÈ" to 6,"cafe" to 6)
  probes.forEach {(query,ordinal) ->require(repository.search(query).any {it.id==fixture.id(ordinal)}) {"Folding probe failed."}}
  onProgress(ScaleCorrectnessPhase.FOLDING,probes.size,probes.size)
  val indexed=index(fixture)
  onProgress(ScaleCorrectnessPhase.INDEX,fixture.notes.size,fixture.notes.size)
  var completedQueries=0
  val queryReports=ProductScaleFixtures.queries.map {query ->
   val expected=members(indexed,query);val expectedIds=expected.map {it.id}.toSet();val actual=repository.search(query)
   require(actual.map {it.id}.toSet()==expected.map {it.id}.toSet()) {"Local query membership differs from fixture oracle."}
   val parsed=searchQuery(query)
   val expectedOrder=if(parsed.expression!=null) repository.dao.search(parsed.expression!!).map {it to referenceScore(it.info)}.sortedWith(compareByDescending<Pair<RankedNote,Double>> {it.second}.thenByDescending {it.first.note.updatedAt}).map {it.first.note.id}.filter {it in expectedIds}
    else expected.sortedByDescending {it.updatedAt}.map {it.id}
   require(actual.map {it.id}==expectedOrder) {"Rank weights/tie ordering changed."}
   require(repository.search(query).map {it.id}==expectedOrder) {"Repeated query order is unstable."}
   onProgress(ScaleCorrectnessPhase.QUERY,++completedQueries,ProductScaleFixtures.queries.size)
   buildJsonObject {put("query",query);put("expected_count",expected.size);put("actual_count",actual.size);put("expected_ids_sha256",ProductScaleFixtures.digest(expected.map {it.id}.sorted()));put("actual_ids_sha256",ProductScaleFixtures.digest(actual.map {it.id}.sorted()));put("ordered_ids_sha256",ProductScaleFixtures.digest(expectedOrder));put("pass",true)}
  }
  fun expectedRows(spec:TimelineSpec):List<Note> {
   val rows=fixture.notes.filter {note ->
    (if(spec.kind==TimelineKind.TRASH) note.trashed else !note.trashed) &&
    (spec.kind!=TimelineKind.HOME && spec.kind!=TimelineKind.LABEL || !note.archived) && (spec.kind!=TimelineKind.ARCHIVE || note.archived) &&
    (spec.kind!=TimelineKind.LABEL || labels(note.body).any {it.key==spec.label || it.key.startsWith(spec.label+"/")}) &&
    (spec.created.fromInclusive==null || note.createdAt>=spec.created.fromInclusive!!) && (spec.created.beforeExclusive==null || note.createdAt<spec.created.beforeExclusive!!)
   }
   val order=when(spec.kind) {TimelineKind.HOME,TimelineKind.LABEL ->compareByDescending<Note> {it.pinned}.thenByDescending {it.createdAt}.thenByDescending {it.id};TimelineKind.TRASH ->compareByDescending<Note> {it.trashedAt}.thenByDescending {it.createdAt}.thenByDescending {it.id};else ->compareByDescending<Note> {it.createdAt}.thenByDescending {it.id}}
   return rows.sortedWith(order)
  }
  val specs=listOf(TimelineSpec(),TimelineSpec(kind=TimelineKind.ARCHIVE),TimelineSpec(kind=TimelineKind.TRASH),TimelineSpec(kind=TimelineKind.LABEL,label="scale/group-0"),TimelineSpec(created=CreatedRange(ProductScaleFixtures.DAY,ProductScaleFixtures.DAY+86_400_000)))
  val timelines=specs.map {spec ->val actual=repository.dao.filteredNotes(timelineQuery(spec)).map {it.id};val expected=expectedRows(spec).map {it.id};require(actual==expected);buildJsonObject {put("kind",spec.kind.name);put("label",spec.label);put("date_boundaries",spec.created.fromInclusive!=null);put("count",actual.size);put("ordered_ids_sha256",ProductScaleFixtures.digest(actual));put("pass",true)}}
  onProgress(ScaleCorrectnessPhase.TIMELINES,timelines.size,timelines.size)
  if(fixture.variant!="A") {
   require(repository.search("zzbroadscale #scale/target").single().id==fixture.id(200))
   require(repository.search("zzbroadscale").take(50).none {it.id==fixture.id(200)}) {"Late-filter fixture no longer exercises outside unfiltered top50."}
   val day=repository.dao.filteredNotes(timelineQuery(TimelineSpec(created=CreatedRange(ProductScaleFixtures.DAY,ProductScaleFixtures.DAY+86_400_000))))
   require(day.map {it.id}==listOf(fixture.id(102),fixture.id(101)))
  }
  onProgress(ScaleCorrectnessPhase.COMPLETE,1,1)
  return buildJsonObject {put("pass",true);put("probes",7);put("queries",JsonArray(queryReports));put("timelines",JsonArray(timelines));put("hierarchy_labels",expectedLabels.size)}
 }
}
