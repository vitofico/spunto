@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package io.theficos.spunto

import android.content.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import io.theficos.spunto.core.design.*
import io.theficos.spunto.core.model.*
import io.theficos.spunto.core.plugins.*
import io.theficos.spunto.data.sync.*
import io.theficos.spunto.data.local.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

@Composable fun LabelsScreen(model:SpuntoViewModel,onLabel:(String)->Unit) {
 val auth by model.auth.collectAsState()
 val labels by model.labels.collectAsState();val online by model.online.collectAsState();val reauth by model.reauthenticate.collectAsState()
 val stateFlow=remember(model.repository) {model.repository.dao.observeState()};val state by stateFlow.collectAsState(null)
 var query by remember {mutableStateOf("")};var selected by remember {mutableStateOf<String?>(null)}
 val latestLabels by rememberUpdatedState(labels)
 val rows=remember(labels,query) {labelSheetRows(labels,query,null)}
 LazyColumn(contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
  item {OutlinedTextField(query,{query=it},Modifier.fillMaxWidth(),label={Text("Find a label")},singleLine=true)}
  if(rows.isEmpty()) item {Text(if(labels.isEmpty()) "Labels grow as you write. Type # in a note." else "No labels match this search.")}
  labelTreeItems(rows,onLabel={key ->if(latestLabels.any {it.key==key}) onLabel(key)},onManageLabel={selected=it})
 }
 selected?.let {path ->
  val target=sharedManagementTarget(auth,state?.serverUrl,online,path,reauth)
  AlertDialog(onDismissRequest={selected=null},title={Text("Manage #$path")},text={Column {Text("Rename and merge labels in the web app, where you can review all affected notes.");Text(target.message,Modifier.padding(top=12.dp),style=MaterialTheme.typography.bodySmall)}},confirmButton={TextButton(enabled=target.url!=null && labels.any {it.path==path},onClick={
   val current=sharedManagementTarget(model.auth.value,state?.serverUrl,model.online.value,path,model.reauthenticate.value)
   current.url?.let {url ->try {model.container.context.startActivity(Intent(Intent.ACTION_VIEW,android.net.Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));selected=null} catch(_:Exception) {model.message.value="The web app could not be opened."}}
  }) {Text("Manage in the web app")}},dismissButton={TextButton(onClick={selected=null}) {Text("Cancel")}})
 }
}
