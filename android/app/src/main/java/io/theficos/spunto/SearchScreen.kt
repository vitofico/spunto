@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package io.theficos.spunto

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.theficos.spunto.core.design.*
import io.theficos.spunto.core.model.*
import io.theficos.spunto.core.plugins.*
import io.theficos.spunto.data.sync.*
import kotlinx.serialization.json.*
import java.time.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun SearchScreen(model:SpuntoViewModel,onNote:(String)->Unit,from:String="",to:String="") {
 var query by rememberSaveable { mutableStateOf("") }; var meaning by remember { mutableStateOf<Set<String>>(emptySet()) }
 // The last results come back at once on return from a note, so the list keeps its place (AN-07).
 var shown by rememberSaveable { mutableStateOf(listOf<String>()) }
 var results by remember { mutableStateOf(shown.mapNotNull { id -> model.notes.value.firstOrNull { it.id==id } }) }
 var answer by remember { mutableStateOf<JsonObject?>(null) }; var asking by remember { mutableStateOf(false) }
 val labels by model.labels.collectAsState(); val auth by model.auth.collectAsState()
 val manifest=model.manifest.collectAsState().value
 val online by model.online.collectAsState()
 val localNotes by model.notes.collectAsState()
 val focus=remember { FocusRequester() }; var focusSpent by rememberSaveable { mutableStateOf(false) }; val focusManager=LocalFocusManager.current
 LaunchedEffect(Unit) { if(!focusSpent) { focusSpent=true;runCatching { focus.requestFocus() } } }
 LaunchedEffect(results) { shown=results.map { it.id } }
 LaunchedEffect(online,manifest) { if(!online || !model.container.manifest.enabled("ask")) answer=null }
 LaunchedEffect(query,from,to,auth,online,localNotes) {
  results=model.repository.search(query).filter { note -> (from.isEmpty() || runCatching { note.createdAt>=LocalDate.parse(from).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrDefault(true)) && (to.isEmpty() || runCatching { note.createdAt<LocalDate.parse(to).plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrDefault(true)) }
  meaning=emptySet()
  if(online && query.isNotBlank()) {
   kotlinx.coroutines.delay(150)
   runCatching { ApiClient(auth!!).request("/api/v1/search",query=buildMap { put("q",query); if(from.isNotBlank()) put("from",from); if(to.isNotBlank()) put("to",to) }).jsonObject }.onSuccess { page -> results=mergeSearchResults(results,page.objects("hits").map(::noteFromWire),localNotes);meaning=page.objects("hits").filter { it.string("match")=="meaning" }.map { it.string("id") }.toSet() }
  }
 }
 val terms=remember(query) { searchTerms(query) }
 Column(Modifier.fillMaxSize()) {
  OutlinedTextField(query,{query=it},Modifier.fillMaxWidth().padding(horizontal=20.dp).focusRequester(focus),placeholder={Text("Search notes")},singleLine=true,
   trailingIcon={ if(query.isNotEmpty()) IconButton(onClick={query=""},modifier=Modifier.semantics { contentDescription="Clear search" }) { Text("×",style=MaterialTheme.typography.titleMedium) } },
   keyboardOptions=KeyboardOptions(imeAction=ImeAction.Search),keyboardActions=KeyboardActions(onSearch={ focusManager.clearFocus() }))
  LazyColumn(Modifier.fillMaxSize(),state=rememberLazyListState(),contentPadding=PaddingValues(start=20.dp,end=20.dp,top=8.dp,bottom=20.dp)) {
   if(!online) item(key="offline") { Text("Offline. Searching this phone by keyword.",Modifier.padding(vertical=8.dp),style=MaterialTheme.typography.bodySmall,color=SpuntoColors.ink3) }
   if(online && query.trim().split(Regex("\\s+")).size>=3 && model.container.manifest.enabled("ask")) item(key="ask") { TextButton(enabled=!asking,onClick={asking=true; model.ask(query,onComplete={asking=false}) { if(model.online.value && model.container.manifest.enabled("ask")) answer=it.jsonObject }},modifier=Modifier.heightIn(min=48.dp)) { Text(if(asking) "Reading your notes…" else "Ask your notes") } }
   if(query.isEmpty() && labels.isNotEmpty()) item(key="labels") {
    // Before typing: the labels in most use, as shortcuts (L1).
    Column(Modifier.padding(vertical=8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
     Text("LABELS",Modifier.semantics { heading();contentDescription="Labels" },style=SpuntoType.overline,color=SpuntoColors.ink3)
     FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) { topLabels(labels).forEach { label -> SuggestionChip(onClick={query="#${label.path} "},label={ Text("#${label.path}") },modifier=Modifier.heightIn(min=48.dp)) } }
    }
   }
   items(results,key={ it.id }) { note -> SearchResult(note,terms,note.id in meaning) { onNote(note.id) } }
   if(query.isNotBlank() && results.isEmpty()) item(key="none") {
    Column(Modifier.padding(vertical=12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
     Text("No notes match ‘${query.trim()}’.",style=MaterialTheme.typography.bodyLarge)
     Text("Try fewer words. Search ignores accents, so perche finds perché.",style=MaterialTheme.typography.bodySmall,color=SpuntoColors.ink3)
    }
   }
  }
 }
 answer?.let { output -> ModalBottomSheet(onDismissRequest={answer=null},scrimColor=MaterialTheme.colorScheme.scrim) { Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) { RenderOutput(model,JsonObject(output+mapOf("plugin_id" to JsonPrimitive("ask"))),onNote);TextButton(onClick={answer=null}) { Text("Close") } } } }
}

/** One result (03 A7): the first line in the reading face, a snippet with the matches marked, then date and labels. */
@Composable private fun SearchResult(note:Note,terms:List<String>,similar:Boolean,onOpen:()->Unit) {
 val mark=SpanStyle(background=SpuntoColors.highlight,color=MaterialTheme.colorScheme.onSurface)
 Column(Modifier.fillMaxWidth().clickable(onClickLabel="Open") { onOpen() }.padding(vertical=12.dp).semantics(mergeDescendants=true) {},verticalArrangement=Arrangement.spacedBy(4.dp)) {
  Text(marked(note.title.ifBlank { "Untitled" },terms,mark),style=SpuntoType.reading,maxLines=2,overflow=TextOverflow.Ellipsis)
  searchSnippet(note.body,terms)?.let { Text(marked(it,terms,mark),style=SpuntoType.readingSmall,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=3,overflow=TextOverflow.Ellipsis) }
  Text(listOfNotNull(noteTime(note.createdAt),labels(note.body).map { "#${it.path}" }.distinct().take(3).joinToString(" ").ifEmpty { null },"≈ similar meaning".takeIf { similar }).joinToString(" · "),style=MaterialTheme.typography.labelSmall,color=SpuntoColors.ink3)
 }
}
private fun marked(text:String,terms:List<String>,style:SpanStyle):AnnotatedString=buildAnnotatedString { append(text);matchRanges(text,terms).forEach { addStyle(style,it.first,it.last+1) } }

internal fun topLabels(labels:List<LabelCount>,count:Int=8)=labels.sortedByDescending { it.count }.take(count)
/** The words a search looks for, folded like the index; `#label` filters are not words. */
internal fun searchTerms(query:String):List<String> {
 var text=query;labels(query).asReversed().forEach { text=text.replaceRange(it.start,it.end," ") }
 return Regex("[\\p{L}\\p{N}]+").findAll(fold(text)).map { it.value }.distinct().toList()
}
/** Where the terms start a word in [text], compared folded (accents and case ignored) and mapped back to the original characters. */
internal fun matchRanges(text:String,terms:List<String>):List<IntRange> {
 if(terms.isEmpty()) return emptyList()
 val folded=StringBuilder();val origin=ArrayList<Int>()
 var i=0
 while(i<text.length) { val next=text.offsetByCodePoints(i,1);fold(text.substring(i,next)).forEach { folded.append(it);origin+=i };i=next }
 val ends=IntArray(origin.size) { index -> (origin.getOrNull(index+1)?.takeIf { it>origin[index] } ?: text.offsetByCodePoints(origin[index],1))-1 }
 return terms.flatMap { term -> Regex(Regex.escape(term)).findAll(folded).filter { it.range.first==0 || !folded[it.range.first-1].isLetterOrDigit() }.map { origin[it.range.first]..ends[it.range.last] } }.sortedBy { it.first }
  .fold(mutableListOf<IntRange>()) { merged,range -> merged.apply { if(isNotEmpty() && range.first<=last().last+1) set(lastIndex,last().first..maxOf(last().last,range.last)) else add(range) } }
}
private val lineMarker=Regex("^\\s*(?:#{1,6}\\s+|>\\s*|[-+*]\\s+(?:\\[[ xX]]\\s+)?|\\d+[.)]\\s+(?:\\[[ xX]]\\s+)?)")
/** The line after the title that holds the first match, cut to about [width] characters around it; without a match, the next line of the note. */
internal fun searchSnippet(body:String,terms:List<String>,width:Int=160):String? {
 val lines=body.lines().map { it.replace(lineMarker,"").trim() }.filter { it.isNotEmpty() }.drop(1)
 val line=lines.firstOrNull { matchRanges(it,terms).isNotEmpty() } ?: return lines.firstOrNull()?.let { if(it.length>width) it.take(width).trimEnd()+"…" else it }
 if(line.length<=width) return line
 val start=(matchRanges(line,terms).first().first-width/3).coerceIn(0,line.length-width)
 return (if(start>0) "…" else "")+line.substring(start,start+width).trim()+(if(start+width<line.length) "…" else "")
}

/** REST relevance order with authoritative unsynced bodies and pending local matches. */
internal fun mergeSearchResults(localMatches:List<Note>,remote:List<Note>,replica:List<Note>):List<Note> {
 val pending=replica.filter { it.pending }.associateBy { it.id }
 return (remote.mapNotNull { hit -> pending[hit.id]?.takeUnless { it.trashed } ?: hit.takeUnless { pending[hit.id]?.trashed==true } } + localMatches.filter { it.pending && !it.trashed }).distinctBy { it.id }
}
