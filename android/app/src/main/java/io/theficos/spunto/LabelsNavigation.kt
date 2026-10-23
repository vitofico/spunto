package io.theficos.spunto

import android.content.Context

import io.theficos.spunto.core.model.LabelCount
import io.theficos.spunto.core.model.fold
import io.theficos.spunto.core.model.labelKey
import io.theficos.spunto.data.local.TimelineKind
import io.theficos.spunto.data.local.TimelineSpec
import io.theficos.spunto.data.local.timelineQuery
import io.theficos.spunto.data.sync.AuthConfig
import kotlinx.serialization.json.*
import java.net.URI
import java.net.URLEncoder

data class SheetView(val pluginId:String,val id:String,val title:String,val filter:JsonObject,val hidden:Boolean) {
 val route get()="view:$pluginId:$id"
}
data class LabelTreeRow(val key:String,val path:String,val count:Int,val depth:Int,val selected:Boolean,val hasChildren:Boolean)
data class LabelsNavigationSnapshot(val route:String,val labels:List<LabelCount>,val manifest:JsonObject,val catalog:JsonObject=JsonObject(emptyMap()))
data class SharedManagementTarget(val url:String?,val message:String)
private val navigationPluginId=Regex("[a-z][a-z0-9-]{1,23}")
private val navigationViewId=Regex("[a-z][a-z0-9-]{0,127}")
private fun text(value:JsonElement?):String?=(value as? JsonPrimitive)?.takeIf {it.isString}?.content

/** Manifest declarations are already active; explicit inactive/malformed facts never gain a view. */
fun declaredActiveViews(manifest:JsonObject):List<SheetView> {
 val hidden=if("hidden_views" !in manifest) emptySet() else {
  val array=manifest["hidden_views"] as? JsonArray ?: return emptyList()
  val paths=array.map {text(it) ?: return emptyList()}
  if(paths.any {val parts=it.split('/');parts.size!=2 || !navigationPluginId.matches(parts[0]) || !navigationViewId.matches(parts[1])}) return emptyList()
  paths.toSet()
 }
 val plugins=manifest["plugins"] as? JsonArray ?: return emptyList()
 val rows=plugins.mapNotNull {it as? JsonObject}
 val duplicates=rows.groupingBy {text(it["id"])}.eachCount().filterValues {it>1}.keys
 return rows.flatMap {plugin ->
  val id=text(plugin["id"]) ?: return@flatMap emptyList()
  if(!navigationPluginId.matches(id) || id in duplicates) return@flatMap emptyList()
  if("enabled" in plugin && (plugin["enabled"] as? JsonPrimitive)?.takeUnless {it.isString}?.booleanOrNull!=true) return@flatMap emptyList()
  if("state" in plugin && text(plugin["state"])!="enabled") return@flatMap emptyList()
  if("switch" in plugin && text(plugin["switch"])!="on") return@flatMap emptyList()
  val contributes=if("contributes" in plugin) plugin["contributes"] as? JsonObject ?: return@flatMap emptyList() else plugin
  val views=contributes["views"] as? JsonArray ?: return@flatMap emptyList()
  val valid=views.mapNotNull {item ->
   val view=item as? JsonObject ?: return@mapNotNull null
   val viewId=text(view["id"]) ?: return@mapNotNull null
   val title=text(view["title"]) ?: return@mapNotNull null
   val filter=view["filter"] as? JsonObject ?: return@mapNotNull null
   if(!navigationViewId.matches(viewId) || title.isBlank() || title.codePointCount(0,title.length)>80 || title.any {it.isISOControl()}) return@mapNotNull null
   if(timelineQuery(TimelineSpec(kind=TimelineKind.PLUGIN,pluginId=id,filter=filter)).sql=="SELECT n.* FROM notes n WHERE 0") return@mapNotNull null
   SheetView(id,viewId,title,filter,"$id/$viewId" in hidden)
  }
  val duplicateViews=valid.groupingBy {it.id}.eachCount().filterValues {it>1}.keys
  valid.filter {it.id !in duplicateViews}
 }
}
fun resolveViewRoute(manifest:JsonObject,route:String):SheetView?=declaredActiveViews(manifest).firstOrNull {it.route==route}
fun isLabelsNavigationRoute(route:String)=route in setOf("timeline","archive","trash","review") || route.startsWith("label:") || route.startsWith("view:")
fun navigationTitle(context:Context,route:String,labels:List<LabelCount>,manifest:JsonObject,catalog:JsonObject=JsonObject(emptyMap())):String=when {
 route=="timeline" -> context.getString(R.string.core_all_notes)
 route=="archive" -> context.getString(R.string.core_archive_title)
 route=="trash" -> context.getString(R.string.core_trash)
 route=="review" -> context.getString(R.string.core_review)
 route.startsWith("label:") -> "#"+(labels.firstOrNull {it.key==route.removePrefix("label:")}?.path ?: route.removePrefix("label:"))
 route.startsWith("view:") -> resolveViewRoute(manifest,route)?.let {localizedViewTitle(context,it,manifest,catalog)} ?: context.getString(R.string.core_view_unavailable)
 else -> route.substringBefore(':').replaceFirstChar {it.uppercase()}
}

/** Only bundled plugin metadata is translated; custom view titles remain server content. */
internal fun localizedViewTitle(context:Context,view:SheetView,manifest:JsonObject,catalog:JsonObject):String {
 val plugin=(manifest["plugins"] as? JsonArray)?.mapNotNull {it as? JsonObject}?.firstOrNull {text(it["id"])==view.pluginId} ?: return view.title
 return localizedPluginView(context.resources,plugin,buildJsonObject {put("id",view.id);put("title",view.title)},catalog)
}

/** Counts are authoritative Room distinct-note counts, never sums of child rows. */
fun labelSheetRows(labels:List<LabelCount>,query:String,selectedKey:String?):List<LabelTreeRow> {
 val valid=labels.filter {it.count>=0 && runCatching {labelKey(it.path)==it.key}.getOrDefault(false)}.distinctBy {it.key}.sortedWith {a,b ->compareLabelKeys(a.key,b.key)}
 val parents=valid.mapNotNull {it.key.substringBeforeLast('/',"").takeIf {parent ->parent.isNotEmpty()}}.toSet()
 val needle=fold(query.trim().removePrefix("#"))
 val matches=valid.filter {needle.isEmpty() || fold(it.path).contains(needle)}.map {it.key}.toSet()
 val shown=if(needle.isEmpty()) matches else matches.flatMap {key ->val parts=key.split('/');parts.indices.map {parts.take(it+1).joinToString("/")}}.toSet()
 return valid.filter {it.key in shown}.map {row ->LabelTreeRow(row.key,row.path,row.count,row.path.count {it=='/'},row.key==selectedKey,row.key in parents)}
}
private fun compareLabelKeys(left:String,right:String):Int {
 var a=0;var b=0
 while(a<left.length && b<right.length) {val x=left.codePointAt(a);val y=right.codePointAt(b);if(x!=y) return x.compareTo(y);a+=Character.charCount(x);b+=Character.charCount(y)}
 return (left.length-a).compareTo(right.length-b)
}

private fun managementRoot(raw:String):String?=runCatching {
 val root=URI(raw);val scheme=root.scheme?.lowercase()
 require(scheme in setOf("https","http") && root.host!=null && root.rawUserInfo==null && root.rawQuery==null && root.rawFragment==null && root.rawPath in setOf("","/") && root.port in -1..65535 && root.port!=0)
 val port=if(root.port==443 && scheme=="https" || root.port==80 && scheme=="http") -1 else root.port
 URI(scheme,null,root.host.lowercase(),port,null,null,null).toASCIIString()
}.getOrNull()
/** Browser cookie sign-in stays separate. This produces a destination, never bridges credentials. */
fun sharedManagementTarget(context:Context,config:AuthConfig?,replicaRoot:String?,online:Boolean,labelPath:String?=null,needsSignIn:Boolean=false):SharedManagementTarget {
 fun unavailable(message:String)=SharedManagementTarget(null,message)
 if(config==null || config.token.isBlank() || needsSignIn) return unavailable(context.getString(R.string.core_manage_sign_in))
 if(!online) return unavailable(context.getString(R.string.core_manage_offline))
 val root=managementRoot(config.serverUrl) ?: return unavailable(context.getString(R.string.core_server_address_missing))
 if(root.startsWith("http://") && !config.httpConsent) return unavailable(context.getString(R.string.core_plain_http_allow))
 if(root!=replicaRoot?.let(::managementRoot)) return unavailable(context.getString(R.string.core_manage_sync))
 val path=if(labelPath==null) "/settings/plugins" else {
  if(runCatching {labelKey(labelPath)}.isFailure) return unavailable(context.getString(R.string.core_label_unavailable))
  "/l/"+labelPath.split('/').joinToString("/") {URLEncoder.encode(it,"UTF-8").replace("+","%20")}
 }
 return SharedManagementTarget(root+path,context.getString(R.string.core_owner_sign_in))
}

/** These owner-only routes intentionally open browser sign-in without a device token. */
fun ownerWebSettingsTarget(context:Context,config:AuthConfig?,replicaRoot:String?,online:Boolean,section:String,needsSignIn:Boolean=false):SharedManagementTarget {
 fun unavailable(message:String)=SharedManagementTarget(null,message)
 if(section !in setOf("data","profile")) return unavailable(context.getString(R.string.core_owner_setting_missing))
 if(config==null || config.token.isBlank() || needsSignIn) return unavailable(context.getString(R.string.core_owner_connect))
 if(!online) return unavailable(context.getString(R.string.core_owner_offline))
 val root=managementRoot(config.serverUrl) ?: return unavailable(context.getString(R.string.core_server_address_missing))
 if(root.startsWith("http://") && !config.httpConsent) return unavailable(context.getString(R.string.core_plain_http_allow))
 if(root!=replicaRoot?.let(::managementRoot)) return unavailable(context.getString(R.string.core_owner_sync))
 return SharedManagementTarget("$root/settings/$section",context.getString(R.string.core_owner_sign_in_separate))
}
