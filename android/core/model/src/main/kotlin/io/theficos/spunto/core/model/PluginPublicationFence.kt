package io.theficos.spunto.core.model

import kotlinx.serialization.json.*

data class PluginPublicationTicket internal constructor(val server:String,val epoch:String,internal val generation:Long)
data class PluginPublication(val manifest:JsonObject,val catalog:JsonObject,val revision:Long,val owner:PluginPublicationTicket)
internal data class PluginAcknowledgement(val switch:String,val revision:Long?,val previousRevision:Long)
class PluginPublicationStage internal constructor(val publication:PluginPublication,internal val serial:Long,internal val acknowledgements:Map<String,PluginAcknowledgement>)

/** Room writes stage privately; only a confirmed committed state may become current. */
class PluginPublicationFence {
 private var generation=0L
 private var serial=0L
 private var ticket:PluginPublicationTicket?=null
 private var identity:Any?=null
 private var value:PluginPublication?=null
 private var acknowledged=emptyMap<String,PluginAcknowledgement>()
 @Synchronized fun transitionOwner(owner:Any,server:String,epoch:String):PluginPublicationTicket {
  val root=server.trimEnd('/')
  if(identity!=owner || ticket?.server!=root || ticket?.epoch!=epoch) {
   identity=owner;ticket=PluginPublicationTicket(root,epoch,++generation);value=null;acknowledged=emptyMap();serial++
  }
  return ticket!!
 }
 /** Standalone pure clients/tests; production tickets come from committed Room owner validation. */
 fun bind(server:String,epoch:String)=transitionOwner("standalone",server,epoch)
 @Synchronized fun current(owner:PluginPublicationTicket):PluginPublication?=value.takeIf {owner==ticket}
 @Synchronized fun invalidate() {identity=null;ticket=null;value=null;acknowledged=emptyMap();generation++;serial++}
 @Synchronized fun <T> withCurrent(block:(PluginPublicationTicket?,PluginPublication?)->T):T=block(ticket,value)
 @Synchronized fun stage(owner:PluginPublicationTicket,manifest:JsonObject,catalog:JsonObject):PluginPublicationStage? {
  if(owner!=ticket) return null
  val nextRevision=revision(manifest) ?: return null
  if(revision(catalog)!=nextRevision) return null
  if(nextRevision<(value?.revision ?: -1)) return value?.let {PluginPublicationStage(it,serial,acknowledged)}
  val plugins=(catalog["plugins"] as? JsonArray)?.map {it as? JsonObject ?: return null} ?: return null
  if(acknowledged.any { (id,ack) -> plugins.firstOrNull {text(it,"id")==id}?.let {text(it,"switch")==ack.switch}!=true && (ack.revision==null || nextRevision<=ack.revision) }) return value?.let {PluginPublicationStage(it,serial,acknowledged)}
  val pending=acknowledged.filterValues {ack -> if(ack.revision!=null) nextRevision<ack.revision else nextRevision<=ack.previousRevision}
  return PluginPublicationStage(PluginPublication(safeManifest(manifest),safeCatalog(catalog),nextRevision,owner),serial,pending)
 }
 @Synchronized fun stageAcknowledgement(owner:PluginPublicationTicket,row:JsonObject):PluginPublicationStage? {
  if(owner!=ticket) return null
  val previous=value ?: return null
  val id=text(row,"id") ?: return null
  val switch=text(row,"switch")?.takeIf {it in setOf("on","off")} ?: return null
  val rows=previous.catalog["plugins"] as? JsonArray ?: return null
  if(rows.none {(it as? JsonObject)?.let {entry -> text(entry,"id")==id}==true}) return null
  val receiptRevision=revision(row)
  if(receiptRevision!=null && receiptRevision<previous.revision) return null
  val actualRevision=receiptRevision ?: previous.revision
  val catalog=safeCatalog(JsonObject(previous.catalog+("plugins_rev" to JsonPrimitive(actualRevision))+("plugins" to JsonArray(rows.map {if((it as? JsonObject)?.let {entry -> text(entry,"id")==id}==true) row else it}))))
  val manifest=JsonObject(previous.manifest+("plugins_rev" to JsonPrimitive(actualRevision))+("plugins" to JsonArray((previous.manifest["plugins"] as? JsonArray).orEmpty().filterNot {(it as? JsonObject)?.let {entry -> text(entry,"id")==id}==true})))
  return PluginPublicationStage(PluginPublication(manifest,catalog,actualRevision,owner),serial,acknowledged+(id to PluginAcknowledgement(switch,receiptRevision,previous.revision)))
 }
 @Synchronized fun commit(stage:PluginPublicationStage):PluginPublication? {
  if(stage.publication.owner!=ticket) return null
  if(stage.serial!=serial) return value.takeIf {it==stage.publication}
  if(value!=stage.publication || acknowledged!=stage.acknowledgements) serial++
  value=stage.publication;acknowledged=stage.acknowledgements
  return value
 }
 /** Convenience for already committed snapshots, never called before a Room write. */
 fun publish(owner:PluginPublicationTicket,manifest:JsonObject,catalog:JsonObject):PluginPublication? {
  val candidate=stage(owner,manifest,catalog) ?: return null
  if(candidate.publication.manifest!=safeManifest(manifest) || candidate.publication.catalog!=safeCatalog(catalog)) return null
  return commit(candidate)
 }
 fun acknowledge(owner:PluginPublicationTicket,row:JsonObject):PluginPublication?=stageAcknowledgement(owner,row)?.let(::commit)
 companion object {
  fun revision(value:JsonObject):Long?=(value["plugins_rev"] as? JsonPrimitive)?.takeUnless {it.isString || !Regex("[0-9]+").matches(it.content)}?.longOrNull
  private fun text(value:JsonObject,key:String):String?=(value[key] as? JsonPrimitive)?.takeIf {it.isString}?.content
  private fun safeCatalog(catalog:JsonObject):JsonObject {
   val rows=(catalog["plugins"] as? JsonArray).orEmpty().map {element ->
    val row=element as? JsonObject ?: return@map element
    val secrets=row["secrets"] as? JsonObject ?: JsonObject(emptyMap())
    val statuses=secrets.mapValues {(_,raw) -> buildJsonObject {(raw as? JsonObject)?.get("set")?.let {value -> (value as? JsonPrimitive)?.takeUnless {it.isString}?.booleanOrNull?.let {put("set",it)}};put("source","environment")}}
    val values=(row["settings"] as? JsonObject)?.filterKeys {it !in secrets}
    JsonObject(row+ ("secrets" to JsonObject(statuses))+ ("settings" to JsonObject(values.orEmpty())))
   }
   return JsonObject(catalog+("plugins" to JsonArray(rows)))
  }
  private fun safeManifest(manifest:JsonObject):JsonObject {
   val rows=(manifest["plugins"] as? JsonArray).orEmpty().map {element ->
    val row=element as? JsonObject ?: return@map element
    val fields=((row["settings"] as? JsonObject)?.get("properties") as? JsonObject).orEmpty()
    val secretKeys=fields.filterValues {(it as? JsonObject)?.get("x-secret")?.let {value -> (value as? JsonPrimitive)?.takeUnless {it.isString}?.booleanOrNull}==true}.keys
    val values=row["settings_values"] as? JsonObject ?: return@map row
    JsonObject(row+("settings_values" to JsonObject(values.filterKeys {it !in secretKeys})))
   }
   return JsonObject(manifest+("plugins" to JsonArray(rows)))
  }
 }
}
val processPluginPublications=PluginPublicationFence()
