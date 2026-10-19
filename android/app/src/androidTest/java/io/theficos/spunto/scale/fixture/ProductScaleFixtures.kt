package io.theficos.spunto.scale.fixture

import io.theficos.spunto.core.model.*
import io.theficos.spunto.data.local.PluginOutputEntity
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.security.MessageDigest
import java.util.UUID

/** Instrumentation-only proposal. Never compiled into the shipped app or an HTTP seed route. */
object ProductScaleFixtures {
 const val SIZE=10_000
 const val DAY=1_791_936_000_000L // 2026-10-14T00:00:00Z
 const val SOURCE_SHA="10c2dadec3cfac2492c104d74306a27873d7c920e755bbbccc5dd7415aead367"
 private val encoding=Json {encodeDefaults=true}
 data class Fixture(val variant:String,val notes:List<Note>,val outputs:List<PluginOutputEntity>,val manifest:JsonObject,val catalog:JsonObject) {
  fun id(ordinal:Int)=notes[ordinal-1].id
  fun payloadHash()=digest(notes.map {encoding.encodeToString(it)})
 }
 fun digest(lines:Iterable<String>):String {
  val hash=MessageDigest.getInstance("SHA-256")
  lines.forEach {line ->hash.update(line.toByteArray(Charsets.UTF_8));hash.update(10.toByte())}
  return hash.digest().joinToString("") {"%02x".format(it)}
 }
 fun deterministicUuid(ordinal:Int,createdAt:Long):String {
  require(ordinal in 1..SIZE && createdAt in 0..0xffffffffffffL)
  return UUID((createdAt shl 16) or 0x7000L or (ordinal.toLong() and 4095),Long.MIN_VALUE or ordinal.toLong()).toString()
 }
 fun generate(variant:String):Fixture {
  require(variant in setOf("A","B","C"))
  val notes=Corpus.generate().map {entry ->
   val ordinal=entry.id.toInt();val changed=variant!="A" && ordinal>6
   val created=if(changed) when(ordinal) {100 ->DAY-1;101 ->DAY;102 ->DAY+86_400_000-1;103 ->DAY+86_400_000;in 200..400 ->DAY-400L*86_400_000+ordinal*1000L;else ->entry.updatedAt} else entry.updatedAt
   val base=if(changed && ordinal in 200..400) "Scale broad match zzbroadscale" else entry.body
   val body=if(changed) base+"\n\n#Scale #Scale/Group-${ordinal%11}/Leaf-${ordinal%3}"+(if(ordinal==200) " #Scale/Target" else "") else base
   val pinned=changed && ordinal%17==0;val archived=changed && ordinal%7==0
   val trashed=changed && ordinal%19==0 || variant=="C" && ordinal==8
   val private=changed && ordinal%23==0 || variant=="C" && ordinal==7
   Note(deterministicUuid(ordinal,created),body,createdAt=created,updatedAt=created,serverRev=1,
    pinned=pinned,archived=archived,trashed=trashed,privateNote=private,pending=false,
    pinnedAt=if(pinned) created else 0,archivedAt=if(archived) created else 0,
    trashedAt=if(trashed) created+ordinal else 0,privateAt=if(private) created else 0)
  }.toList()
  fun output(ordinal:Int,plugin:String,kind:String,payload:JsonObject=JsonObject(emptyMap())):PluginOutputEntity {
   val id="$plugin/${notes[ordinal-1].id}/$kind"
   return PluginOutputEntity("annotation",id,notes[ordinal-1].id,buildJsonObject {put("id",id);put("plugin",plugin);put("plugin_id",plugin);put("note_id",notes[ordinal-1].id);put("kind",kind);put("payload",payload)}.toString())
  }
  val outputs=if(variant=="C") listOf(
   output(7,"scale-live","slip"),output(8,"scale-live","slip"),output(10,"scale-off","slip"),output(11,"scale-live","slip"),
   output(11,"scale-live","meta",buildJsonObject {put("type","text");put("text","Synthetic scale metadata")}),
   output(11,"scale-live","member:focus"),output(10,"scale-off","member:focus"),
   PluginOutputEntity("suggestion","scale-proposal",notes[10].id,buildJsonObject {put("id","scale-proposal");put("plugin","scale-live");put("plugin_id","scale-live");put("note_id",notes[10].id);put("note_version",1);put("status","pending");put("action","append_text");put("payload",buildJsonObject {put("text","Synthetic proposal text")})}.toString())
  ) else emptyList()
  val live=buildJsonObject {put("id","scale-live");put("name","Scale fixture");put("api","1.0");put("version","fixture");put("state","enabled");put("switch","on");put("permissions",buildJsonArray {add("annotate");add("suggest")});put("granted",buildJsonArray {add("annotate");add("suggest")});put("contributes",buildJsonObject {put("slip",buildJsonArray {add(buildJsonObject {put("title","Scale slip")})});put("views",buildJsonArray {add(buildJsonObject {put("id","focus");put("title","Scale focus");put("filter",buildJsonObject {put("member","focus")})})})})}
  val inactive=JsonObject(live+mapOf("id" to JsonPrimitive("scale-off"),"state" to JsonPrimitive("disabled"),"switch" to JsonPrimitive("off")))
  val manifest=buildJsonObject {put("plugins_rev",1);put("plugins",JsonArray(if(variant=="C") listOf(live) else emptyList()))}
  val catalog=buildJsonObject {put("plugins_rev",1);put("plugins",JsonArray(if(variant=="C") listOf(live,inactive) else emptyList()))}
  return Fixture(variant,notes,outputs,manifest,catalog)
 }
 val queries=listOf("idea","progetto","meeting","server","nasoni","obtainium","zettelkasten","ornitorinco","idea app","riunione lunedi","deploy staging","book chapter","perche","citta","CAFFÈ","piu","\"lista della spesa\"","\"pull request\"","\"server di staging\"","ri","riu","riun","riuni","de","dep","depl","zzscalenomatchxyz","#scale/group-0","#scale","zzbroadscale #scale/target")
}
