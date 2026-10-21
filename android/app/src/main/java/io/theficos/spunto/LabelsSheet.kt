package io.theficos.spunto

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Reused by the picker and label page; counts already include distinct descendant notes. */
internal fun LazyListScope.labelTreeItems(rows:List<LabelTreeRow>,onLabel:(String)->Unit,onManageLabel:((String)->Unit)?=null,managementAvailable:Boolean=true) {
 items(rows,key={"label:"+it.key}) {row ->
  Row(Modifier.fillMaxWidth().padding(start=(row.depth.coerceAtMost(4)*16).dp),verticalAlignment=Alignment.CenterVertically) {
   TextButton(onClick={onLabel(row.key)},colors=ButtonDefaults.textButtonColors(containerColor=if(row.selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent),modifier=Modifier.weight(1f).heightIn(min=48.dp).testTag("label:"+row.key).semantics {selected=row.selected;contentDescription="#${row.path}, ${row.count} notes"}) {
    Text("#${row.path.substringAfterLast('/')}",Modifier.weight(1f).padding(end=8.dp),style=MaterialTheme.typography.bodyMedium,maxLines=1,overflow=TextOverflow.Ellipsis)
    Text(row.count.toString(),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
   }
   onManageLabel?.let {manage ->TextButton(enabled=managementAvailable,onClick={manage(row.path)},modifier=Modifier.heightIn(min=48.dp).semantics {contentDescription="Manage #${row.path} in the web app"}) {Text("Manage")}}
  }
 }
}

/** Stateless navigation callbacks; opening, searching and dismissing never save or navigate. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun LabelsSheet(
 snapshot:LabelsNavigationSnapshot,onRoute:(String)->Unit,onSettings:()->Unit,
 onManageLabels:(String)->Unit,onManageViews:()->Unit,onDismiss:()->Unit,
 managementAvailable:Boolean=false,managementMessage:String="Connect and sync with your server to manage shared labels and views.",
) {
 var query by remember {mutableStateOf("")};var showMore by remember {mutableStateOf(false)}
 val latest by rememberUpdatedState(snapshot)
 val views=remember(snapshot.manifest) {declaredActiveViews(snapshot.manifest)}
 val rows=remember(snapshot.labels,query,snapshot.route) {labelSheetRows(snapshot.labels,query,snapshot.route.takeIf {it.startsWith("label:")}?.removePrefix("label:"))}
 ModalBottomSheet(onDismissRequest=onDismiss,scrimColor=MaterialTheme.colorScheme.scrim) {
  Column(Modifier.fillMaxWidth().fillMaxHeight(.88f).imePadding().padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
   Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {Text("Labels and views",Modifier.weight(1f),style=MaterialTheme.typography.titleLarge);TextButton(onClick=onDismiss) {Text("Close")}}
   OutlinedTextField(query,{query=it},Modifier.fillMaxWidth(),label={Text("Find a label")},singleLine=true)
   LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(bottom=24.dp)) {
    items(listOf("timeline" to "All notes","archive" to "Archive","trash" to "Trash","review" to "Review"),key={it.first}) {(route,title) ->
     TextButton(onClick={onRoute(route)},colors=ButtonDefaults.textButtonColors(containerColor=if(snapshot.route==route) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent),modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).semantics {selected=snapshot.route==route}) {Text(title,Modifier.fillMaxWidth())}
    }
    if(views.isNotEmpty()) {
     item {Text("Views",Modifier.padding(top=16.dp,bottom=8.dp),style=MaterialTheme.typography.titleMedium)}
     items(views.filter {!it.hidden || showMore},key={it.route}) {view ->
      TextButton(onClick={resolveViewRoute(latest.manifest,view.route)?.takeIf {!it.hidden || showMore}?.let {onRoute(it.route)}},colors=ButtonDefaults.textButtonColors(containerColor=if(snapshot.route==view.route) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent),modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).semantics {selected=snapshot.route==view.route}) {Text(view.title,Modifier.fillMaxWidth())}
     }
     if(views.any {it.hidden}) item {TextButton(onClick={showMore=!showMore}) {Text(if(showMore) "Show fewer views" else "Show more views")}}
     item {TextButton(enabled=managementAvailable,onClick=onManageViews) {Text("Manage views in the web app")}}
    }
    item {Text("Labels",Modifier.padding(top=16.dp,bottom=8.dp),style=MaterialTheme.typography.titleMedium)}
    if(rows.isEmpty()) item {Text(if(snapshot.labels.isEmpty()) "Labels grow as you write. Type # in a note." else "No labels match this search.",style=MaterialTheme.typography.bodyMedium)}
    labelTreeItems(rows,onLabel={key ->if(latest.labels.any {it.key==key}) onRoute("label:$key")},onManageLabel={path ->if(managementAvailable && latest.labels.any {it.path==path}) onManageLabels(path)},managementAvailable=managementAvailable)
    item {Text(managementMessage,Modifier.padding(vertical=12.dp),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
    item {TextButton(onClick=onSettings,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) {Text("Settings",Modifier.fillMaxWidth())}}
   }
  }
 }
}
