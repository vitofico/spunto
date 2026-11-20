package io.theficos.spunto

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import io.theficos.spunto.core.plugins.bool
import io.theficos.spunto.core.plugins.objects
import io.theficos.spunto.core.plugins.string
import kotlinx.serialization.json.JsonObject

internal fun libraryCommandAvailable(manifest:JsonObject,pluginId:String,commandId:String):Boolean {
 val plugin=manifest.objects("plugins").singleOrNull {it.string("id")==pluginId} ?: return false
 return plugin.bool("enabled",true) && plugin.string("state") in setOf("","enabled") &&
  plugin.string("origin")!="operator" && plugin.string("source_kind") !in setOf("folder","entrypoint") &&
  (plugin["contributes"] as? JsonObject)?.objects("commands")?.any {it.string("id")==commandId}==true
}

@Composable internal fun AskNotesButton(onClick:()->Unit) {
 val resources=LocalContext.current.resources
 IconButton(onClick=onClick,modifier=Modifier.size(48.dp)) {
  Icon(painterResource(R.drawable.ic_ask),contentDescription=resources.getString(R.string.core_ask_notes),modifier=Modifier.size(21.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)
 }
}

@Composable internal fun LibraryStatsDialog(model:SpuntoViewModel,onNote:(String)->Unit,onClose:()->Unit) {
 val resources=LocalContext.current.resources
 val online by model.online.collectAsState()
 var result by remember {mutableStateOf<JsonObject?>(null)}
 var error by remember {mutableStateOf<String?>(null)}
 var retry by remember {mutableIntStateOf(0)}
 LaunchedEffect(online,retry) {
  if(online && result==null) {
   error=null
   runCommand(model,"library-stats","summary",JsonObject(emptyMap()),{result=it},scope=this,onError={error=it},requireOwner=true).join()
  }
 }
 val summary=result
 if(summary!=null) {
  CommandResultSheet(model,summary,{onClose();onNote(it)},{result=it},onClose)
 } else {
  AlertDialog(onDismissRequest=onClose,title={Text(resources.getString(R.string.settings_plugin_library_stats_name))},text={
   val message=error
   when {
    !online -> Text(resources.getString(R.string.library_stats_connect))
    message!=null -> Text(message,color=MaterialTheme.colorScheme.error)
    else -> LinearProgressIndicator(Modifier.fillMaxWidth())
   }
  },confirmButton={
   if(online && error!=null) TextButton(onClick={retry++}) {Text(resources.getString(R.string.core_ask_retry))}
   else TextButton(onClick=onClose) {Text(resources.getString(R.string.core_close))}
  },dismissButton={
   if(online && error!=null) TextButton(onClick=onClose) {Text(resources.getString(R.string.core_close))}
  })
 }
}
