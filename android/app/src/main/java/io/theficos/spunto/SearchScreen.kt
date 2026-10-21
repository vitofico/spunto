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
import java.time.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun SearchScreen(model:SpuntoViewModel,onNote:(String)->Unit,from:String="",to:String="") {
 var query by remember { mutableStateOf("") }; var results by remember { mutableStateOf<List<Note>>(emptyList()) }; var meaning by remember { mutableStateOf<Set<String>>(emptySet()) }
 var answer by remember { mutableStateOf<JsonObject?>(null) }; var asking by remember { mutableStateOf(false) }
 val labels by model.labels.collectAsState(); val auth by model.auth.collectAsState(); val sync by model.status.collectAsState()
 val manifest=model.manifest.collectAsState().value
 val online by model.online.collectAsState()
 val localNotes by model.notes.collectAsState()
 LaunchedEffect(online,manifest) { if(!online || !model.container.manifest.enabled("ask")) answer=null }
 LaunchedEffect(query,from,to,auth,online,localNotes) {
  results=model.repository.search(query).filter { note -> (from.isEmpty() || runCatching { note.createdAt>=LocalDate.parse(from).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrDefault(true)) && (to.isEmpty() || runCatching { note.createdAt<LocalDate.parse(to).plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrDefault(true)) }
  meaning=emptySet()
  if(online && query.isNotBlank()) {
   kotlinx.coroutines.delay(150)
   runCatching { ApiClient(auth!!).request("/api/v1/search",query=buildMap { put("q",query); if(from.isNotBlank()) put("from",from); if(to.isNotBlank()) put("to",to) }).jsonObject }.onSuccess { page -> results=mergeSearchResults(results,page.objects("hits").map(::noteFromWire),localNotes);meaning=page.objects("hits").filter { it.string("match")=="meaning" }.map { it.string("id") }.toSet() }
  }
 }
 Column(Modifier.padding(20.dp)) {
  OutlinedTextField(query,{query=it},Modifier.fillMaxWidth(),label={Text("Search notes")},singleLine=true)
  if(!online) Text("Offline. Searching this phone by keyword.",style=MaterialTheme.typography.bodySmall)
  if(online && query.trim().split(Regex("\\s+")).size>=3 && model.container.manifest.enabled("ask")) TextButton(enabled=!asking,onClick={asking=true; model.ask(query,onComplete={asking=false}) { if(model.online.value && model.container.manifest.enabled("ask")) answer=it.jsonObject }}) { Text(if(asking) "Reading your notes…" else "Ask your notes") }
  if(query.isEmpty()) labels.take(8).forEach { TextButton(onClick={query="#${it.path}"}) { Text("#${it.path}") } }
  LazyColumn(verticalArrangement=Arrangement.spacedBy(16.dp)) { items(results,key={it.id}) { note -> if(note.id in meaning) Text("≈ similar meaning",style=MaterialTheme.typography.bodySmall); NoteEntry(model,note,onNote) }; if(query.isNotEmpty() && results.isEmpty()) item { Text("No notes match this search.") } }
 }
 answer?.let { output -> ModalBottomSheet(onDismissRequest={answer=null},scrimColor=MaterialTheme.colorScheme.scrim) { Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) { RenderOutput(model,JsonObject(output+mapOf("plugin_id" to JsonPrimitive("ask"))),onNote);TextButton(onClick={answer=null}) { Text("Close") } } } }
}

/** REST relevance order with authoritative unsynced bodies and pending local matches. */
internal fun mergeSearchResults(localMatches:List<Note>,remote:List<Note>,replica:List<Note>):List<Note> {
 val pending=replica.filter { it.pending }.associateBy { it.id }
 return (remote.mapNotNull { hit -> pending[hit.id]?.takeUnless { it.trashed } ?: hit.takeUnless { pending[hit.id]?.trashed==true } } + localMatches.filter { it.pending && !it.trashed }).distinctBy { it.id }
}
