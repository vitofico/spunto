package io.theficos.spunto

import io.theficos.spunto.core.model.*
import io.theficos.spunto.data.sync.AuthConfig
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.Timeout
import java.util.concurrent.TimeUnit

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(application=android.app.Application::class,sdk=[28])
class LabelsNavigationTest {
 private val context=androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
 @get:Rule val timeout=Timeout(10,TimeUnit.MINUTES)
 private val labels=listOf(LabelCount("project","Project",3),LabelCount("project/spunto","Project/Spunto",2),LabelCount("project/other","Project/Other",2),LabelCount("ideas","Ideas",1))
 private fun plugin(id:String,view:String,title:String,state:String?=null,filter:JsonObject=buildJsonObject {put("no_labels",true)})=buildJsonObject {
  put("id",id);state?.let {put("state",it)}
  put("contributes",buildJsonObject {put("views",buildJsonArray {add(buildJsonObject {put("id",view);put("title",title);put("filter",filter)})})})
 }
 private fun manifest(vararg plugins:JsonObject,hidden:JsonElement?=buildJsonArray {add("label-suggestions/unlabeled")})=buildJsonObject {
  put("plugins_rev",17);put("plugins",JsonArray(plugins.toList()));hidden?.let {put("hidden_views",it)}
 }
 private fun active()=manifest(plugin("label-suggestions","unlabeled","Unlabeled"),plugin("tasks","open","Open tasks",filter=buildJsonObject {put("open_tasks",true)}))
 private fun config(root:String="https://synthetic.invalid",consent:Boolean=false)=AuthConfig(root,"synthetic-label-token","synthetic-device","Synthetic labels",consent)
 @Test fun hierarchyUsesDistinctAuthoritativeCountsAndExactSelectedKey() {
  val rows=labelSheetRows(labels,"","project/spunto")
  assertEquals(listOf("ideas","project","project/other","project/spunto"),rows.map {it.key})
  assertEquals(3,rows.first {it.key=="project"}.count);assertTrue(rows.first {it.key=="project"}.hasChildren)
  assertEquals(listOf("project/spunto"),rows.filter {it.selected}.map {it.key});assertEquals(1,rows.last().depth)
 }
 @Test fun childSearchKeepsOnlyRealAncestorContextAndDoesNotInventCounts() {
  assertEquals(listOf("project","project/spunto"),labelSheetRows(labels,"#SPUNTO",null).map {it.key})
  assertEquals(3,labelSheetRows(labels,"spunto",null).first().count)
  val leaf=labels.filter {it.key=="project/spunto"}
  assertEquals(listOf("project/spunto"),labelSheetRows(leaf,"spunto",null).map {it.key})
  assertTrue(labelSheetRows(labels,"absent",null).isEmpty());assertTrue(labelSheetRows(emptyList(),"",null).isEmpty())
 }
 @Test fun sharedUnicodeFoldSearchAndFiveSegmentDepthStayCanonical() {
  val paths=listOf("Caffè/Idee","Straße","\uD803\uDD50/Idea","a/b/c/d/e")
  val input=paths.map {LabelCount(labelKey(it),it,7)}
  assertEquals("Caffè/Idee",labelSheetRows(input,"CAFFE",null).single().path)
  assertEquals("Straße",labelSheetRows(input,"STRASSE",null).single().path)
  assertEquals("\uD803\uDD50/Idea",labelSheetRows(input,"\uD803\uDD70",null).single().path)
  assertEquals(4,labelSheetRows(input,"a/b/c/d/e",null).single().depth)
  assertTrue(labelSheetRows(input+LabelCount("wrong","Safe",-1)+LabelCount("a/b/c/d/e/f","a/b/c/d/e/f",1),"",null).all {it.count==7 && it.depth<=4})
 }
 @Test fun hiddenViewsRemainResolvableWithoutChangingCachedSettings() {
  val cached=active();val before=cached.toString();val views=declaredActiveViews(cached)
  assertEquals(listOf("Open tasks"),views.filter {!it.hidden}.map {it.title});assertEquals(listOf("Unlabeled"),views.filter {it.hidden}.map {it.title})
  assertEquals("Unlabeled",resolveViewRoute(cached,"view:label-suggestions:unlabeled")!!.title)
  assertEquals(before,cached.toString());assertNull(resolveViewRoute(cached,"view:missing:view"))
 }
 @Test fun inactiveDisabledMalformedAndDuplicateDeclarationsFailClosed() {
  for(state in listOf("waiting","disabled","off","failed","removed")) assertTrue(declaredActiveViews(manifest(plugin("tasks","open","Open tasks",state))).isEmpty())
  val task=plugin("tasks","open","Open tasks")
  listOf(JsonPrimitive(false),JsonPrimitive("true"),JsonNull).forEach {assertTrue(declaredActiveViews(manifest(JsonObject(task+ ("enabled" to it)))).isEmpty())}
  assertTrue(declaredActiveViews(manifest(task,task)).isEmpty())
  assertTrue(declaredActiveViews(manifest(plugin("bad/id","open","Open tasks"))).isEmpty())
  assertTrue(declaredActiveViews(manifest(plugin("tasks","bad:id","Open tasks"))).isEmpty())
  assertTrue(declaredActiveViews(manifest(plugin("tasks","open","Open tasks",filter=buildJsonObject {put("open_tasks","true")}))).isEmpty())
  assertTrue(declaredActiveViews(manifest(plugin("tasks","open","Open tasks",filter=buildJsonObject {put("unknown",true)}))).isEmpty())
  listOf(JsonNull,JsonPrimitive("tasks/open"),buildJsonArray {add(true)},buildJsonArray {add("../open")}).forEach {assertTrue(declaredActiveViews(manifest(task,hidden=it)).isEmpty())}
  assertEquals(1,declaredActiveViews(manifest(task,hidden=null)).size)
 }
 @Test fun titleAndRoutesDistinguishUnavailableViewsAndExactLabelSelection() {
  assertEquals("All notes",navigationTitle(context,"timeline",labels,active()));assertEquals("Archive",navigationTitle(context,"archive",labels,active()));assertEquals("Trash",navigationTitle(context,"trash",labels,active()));assertEquals("Review",navigationTitle(context,"review",labels,active()))
  assertEquals("#Project/Spunto",navigationTitle(context,"label:project/spunto",labels,active()))
  assertEquals("Unlabeled",navigationTitle(context,"view:label-suggestions:unlabeled",labels,active()))
  assertEquals("View unavailable",navigationTitle(context,"view:tasks:open",labels,manifest()))
  assertTrue(isLabelsNavigationRoute("label:project"));assertTrue(isLabelsNavigationRoute("view:tasks:open"));assertFalse(isLabelsNavigationRoute("note:fixture"));assertFalse(isLabelsNavigationRoute("search"));assertFalse(isLabelsNavigationRoute("appearance"))
 }
 @Test fun managementEncodesNestedSegmentsAndNeverBridgesDeviceCredentials() {
  val auth=config("https://SYNTHETIC.invalid:443/")
  val target=sharedManagementTarget(context,auth,"https://synthetic.invalid",true,"Caffè/Spunto")
  assertEquals("https://synthetic.invalid/l/Caff%C3%A8/Spunto",target.url)
  assertFalse(target.url!!.contains(auth.token));assertTrue(target.message.contains("Owner sign-in"))
  assertEquals("https://synthetic.invalid/settings/plugins",sharedManagementTarget(context,auth,"https://synthetic.invalid/",true).url)
  assertEquals("http://synthetic.invalid:18215/l/project/spunto",sharedManagementTarget(context,config("http://synthetic.invalid:18215",true),"http://synthetic.invalid:18215/",true,"project/spunto").url)
 }
 @Test fun offlineUnboundOtherServerAndReauthKeepLocalBrowsingButRefuseWebManagement() {
  val auth=config()
  assertNull(sharedManagementTarget(context,null,auth.serverUrl,true).url);assertNull(sharedManagementTarget(context,auth.copy(token=""),auth.serverUrl,true).url)
  assertNull(sharedManagementTarget(context,auth,auth.serverUrl,false).url);assertNull(sharedManagementTarget(context,auth,null,true).url);assertNull(sharedManagementTarget(context,auth,"https://another.synthetic",true).url);assertNull(sharedManagementTarget(context,auth,auth.serverUrl,true,needsSignIn=true).url)
  assertNull(sharedManagementTarget(context,config("http://synthetic.invalid"),"http://synthetic.invalid",true).url)
  assertEquals(4,labelSheetRows(labels,"",null).size);assertEquals(2,declaredActiveViews(active()).size)
 }
 @Test fun managementRejectsUnsafeRootsAndInvalidLabelPathsWithoutProducingAnyUrl() {
  for(root in listOf("javascript:alert(1)","https://user:password@synthetic.invalid","https://synthetic.invalid/path","https://synthetic.invalid?token=fixture","https://synthetic.invalid#fragment","https://synthetic.invalid:0")) assertNull(sharedManagementTarget(context,config(root),root,true).url)
  for(path in listOf("../settings","project//spunto","safe?token=fixture","#label","a/b/c/d/e/f")) assertNull(sharedManagementTarget(context,config(),"https://synthetic.invalid",true,path).url)
 }
}
