package io.theficos.spunto

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.theficos.spunto.core.design.*

/** Reused by the picker and label page; counts already include distinct descendant notes. Long-press manages a label. */
internal fun LazyListScope.labelTreeItems(rows:List<LabelTreeRow>,onLabel:(String)->Unit,onManageLabel:((String)->Unit)?=null,managementAvailable:Boolean=true) {
 items(rows,key={"label:"+it.key}) {row ->
  SheetRow("#${row.path.substringAfterLast('/')}",row.selected,Modifier.testTag("label:"+row.key),count=row.count,depth=row.depth,description="#${row.path}, ${notesCount(row.count)}",
   onLongClick=onManageLabel?.takeIf {managementAvailable}?.let {manage -> {manage(row.path)}},longClickLabel="Manage #${row.path} in the web app") {onLabel(row.key)}
 }
}
/** A6 row: ink text after its glyph, if any, the count in ink-3 at the right, the active one in the accent on an accent-soft pill; 48 dp tall, 16 dp per level. */
@OptIn(ExperimentalFoundationApi::class)
@Composable private fun SheetRow(title:String,selected:Boolean,modifier:Modifier=Modifier,count:Int?=null,depth:Int=0,description:String?=null,glyph:Int?=null,onLongClick:(()->Unit)?=null,longClickLabel:String?=null,onClick:()->Unit) {
 val ink=if(selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
 Row(modifier.fillMaxWidth().padding(start=(depth.coerceAtMost(4)*16).dp).heightIn(min=48.dp).clip(CircleShape).background(if(selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
  .combinedClickable(role=Role.Button,onLongClickLabel=longClickLabel,onLongClick=onLongClick,onClick=onClick).semantics {this.selected=selected;description?.let {contentDescription=it}}.padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically) {
  glyph?.let {Icon(painterResource(it),contentDescription=null,modifier=Modifier.padding(end=16.dp).size(24.dp),tint=ink)}
  Text(title,Modifier.weight(1f).padding(end=8.dp),style=MaterialTheme.typography.bodyLarge,color=ink,maxLines=1,overflow=TextOverflow.Ellipsis)
  count?.let {Text(it.toString(),style=MaterialTheme.typography.labelMedium,color=if(selected) ink else SpuntoColors.ink3)}
 }
}
@Composable private fun SheetOverline(text:String,action:(@Composable ()->Unit)?=null) =
 Row(Modifier.fillMaxWidth().padding(top=16.dp,start=12.dp).heightIn(min=32.dp),verticalAlignment=Alignment.CenterVertically) {
  Text(text.uppercase(),Modifier.weight(1f).semantics {heading();contentDescription=text},style=SpuntoType.overline,color=SpuntoColors.ink3);action?.invoke()
 }

/** Stateless navigation callbacks; opening, searching and dismissing never save or navigate. The drag handle closes it (A6). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun LabelsSheet(
 snapshot:LabelsNavigationSnapshot,onRoute:(String)->Unit,onSettings:()->Unit,
 onManageLabels:(String)->Unit,onManageViews:()->Unit,onDismiss:()->Unit,
 managementAvailable:Boolean=false,managementMessage:String="Connect and sync with your server to manage shared labels and views.",reviewWaiting:Int=0,allNotes:Int?=null,
) {
 var query by remember {mutableStateOf("")};var showMore by remember {mutableStateOf(false)}
 val latest by rememberUpdatedState(snapshot)
 val views=remember(snapshot.manifest) {declaredActiveViews(snapshot.manifest)}
 val rows=remember(snapshot.labels,query,snapshot.route) {labelSheetRows(snapshot.labels,query,snapshot.route.takeIf {it.startsWith("label:")}?.removePrefix("label:"))}
 // Fully open from the start, and one scrolling column, so large font sizes still reach every row.
 ModalBottomSheet(onDismissRequest=onDismiss,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),scrimColor=MaterialTheme.colorScheme.scrim) {
  LazyColumn(Modifier.fillMaxWidth().fillMaxHeight(.9f).imePadding(),contentPadding=PaddingValues(start=8.dp,end=8.dp,bottom=24.dp)) {
   item(key="find") {OutlinedTextField(query,{query=it},Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=4.dp),placeholder={Text("Find a label")},singleLine=true)}
   items(listOfNotNull(Triple("timeline","All notes",R.drawable.ic_notes),Triple("archive","Archive",R.drawable.ic_archive),Triple("trash","Trash",R.drawable.ic_trash),Triple("review","Review",R.drawable.ic_inbox).takeIf {reviewWaiting>0 || snapshot.route=="review"}),key={it.first}) {(route,title,glyph) ->
    val count=when(route) {"timeline" -> allNotes;"review" -> reviewWaiting;else -> null}
    SheetRow(title,snapshot.route==route,count=count,description=count?.let {"$title, ${notesCount(it)}"}.takeIf {route=="timeline"},glyph=glyph) {onRoute(route)}
   }
   if(views.isNotEmpty()) {
    item(key="views") {SheetOverline("Views") {if(views.any {it.hidden}) TextButton(onClick={showMore=!showMore}) {Text(if(showMore) "Show fewer views" else "Show more views")}}}
    items(views.filter {!it.hidden || showMore},key={it.route}) {view ->
     SheetRow(view.title,snapshot.route==view.route,onLongClick=onManageViews.takeIf {managementAvailable},longClickLabel="Manage views in the web app") {resolveViewRoute(latest.manifest,view.route)?.takeIf {!it.hidden || showMore}?.let {onRoute(it.route)}}
    }
   }
   item(key="labels") {SheetOverline("Labels")}
   if(rows.isEmpty()) item(key="no-labels") {Text(if(snapshot.labels.isEmpty()) "Labels grow as you write. Type # in a note." else "No labels match this search.",Modifier.padding(horizontal=12.dp,vertical=8.dp),style=MaterialTheme.typography.bodyMedium,color=SpuntoColors.ink3)}
   labelTreeItems(rows,onLabel={key ->if(latest.labels.any {it.key==key}) onRoute("label:$key")},onManageLabel={path ->if(managementAvailable && latest.labels.any {it.path==path}) onManageLabels(path)},managementAvailable=managementAvailable)
   item(key="manage") {Text(if(managementAvailable && rows.isNotEmpty()) "Long-press a label to rename, merge or remove it in the web app." else managementMessage,Modifier.padding(horizontal=12.dp,vertical=12.dp),style=MaterialTheme.typography.bodySmall,color=SpuntoColors.ink3)}
   item(key="settings") {HorizontalDivider(Modifier.padding(horizontal=12.dp),color=MaterialTheme.colorScheme.outlineVariant);SheetRow("Settings",false) {onSettings()}}
  }
 }
}
