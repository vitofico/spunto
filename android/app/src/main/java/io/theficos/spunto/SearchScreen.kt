@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package io.theficos.spunto

import androidx.compose.ui.platform.LocalContext

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import java.time.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun SearchScreen(model:SpuntoViewModel,onNote:(String)->Unit,from:String="",to:String="") {
 val context=LocalContext.current
 var query by rememberSaveable { mutableStateOf("") }; var meaning by remember { mutableStateOf<Set<String>>(emptySet()) }
 // The last results come back at once on return from a note, so the list keeps its place (AN-07).
 var shown by rememberSaveable { mutableStateOf(listOf<String>()) }
 var results by remember { mutableStateOf(shown.mapNotNull { id -> model.notes.value.firstOrNull { it.id==id } }) }
 var answer by remember { mutableStateOf<JsonObject?>(null) }; var asking by remember { mutableStateOf(false) }
 var hasMore by remember {mutableStateOf(false)};var loadingPage by remember {mutableStateOf(false)};var moreRequests by remember {mutableIntStateOf(0)}
 // L1: labels used most in the last 30 days first, then by overall use; S2: the last searches on this phone.
 val labels by remember { model.repository.dao.labelsByUse(System.currentTimeMillis()-30*86_400_000L,8) }.collectAsState(emptyList()); val auth by model.auth.collectAsState()
 val preferences=model.container.preferences
 var recent by remember { mutableStateOf(recentSearches(preferences.getString("recent-searches",null))) }
 fun saveSearch(text:String) { recent=rememberSearch(recent,text);preferences.edit().putString("recent-searches",JsonArray(recent.map(::JsonPrimitive)).toString()).apply() }
 val manifest=model.manifest.collectAsState().value
 val online by model.online.collectAsState()
 val localNotes by model.notes.collectAsState()
 val focus=remember { FocusRequester() }; var focusSpent by rememberSaveable { mutableStateOf(false) }; val focusManager=LocalFocusManager.current
 LaunchedEffect(Unit) { if(!focusSpent) { focusSpent=true;runCatching { focus.requestFocus() } } }
 LaunchedEffect(results) { shown=results.map { it.id } }
 LaunchedEffect(online,manifest) { if(!online || !model.container.manifest.enabled("ask")) answer=null }
 LaunchedEffect(query,from,to,auth,online,localNotes) {
  results=model.repository.search(query).filter { note -> (from.isEmpty() || runCatching { note.createdAt>=LocalDate.parse(from).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrDefault(true)) && (to.isEmpty() || runCatching { note.createdAt<LocalDate.parse(to).plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrDefault(true)) }
  val localMatches=results
  meaning=emptySet();hasMore=false;loadingPage=false
  val config=auth
  if(online && config!=null && query.isNotBlank()) {
   var requested=moreRequests
   kotlinx.coroutines.delay(150)
   searchServerPages(request={cursor ->
    loadingPage=true
    try {ApiClient(config).request("/api/v1/search",query=buildMap {put("q",query);put("include_archived","true");if(from.isNotBlank()) put("from",from);if(to.isNotBlank()) put("to",to);cursor?.let {put("cursor",it)}}).jsonObject}
    finally {loadingPage=false}
   },awaitMore={snapshotFlow {moreRequests}.first {it!=requested};requested=moreRequests},show={pages ->
    results=mergeSearchResults(localMatches,pages.hits,localNotes);meaning=pages.meaning;hasMore=pages.cursor!=null
   },failed={error ->model.message.value=if(error is ApiException) apiErrorMessage(context.resources,error) else context.getString(R.string.sys_server_unreachable)})
  }
 }
 val terms=remember(query) { searchTerms(query) }
 Column(Modifier.fillMaxSize()) {
  OutlinedTextField(query,{query=it},Modifier.fillMaxWidth().padding(horizontal=SpuntoSpacing.s20).focusRequester(focus),placeholder={Text(context.getString(R.string.core_search_notes))},singleLine=true,
   trailingIcon={ if(query.isNotEmpty()) IconButton(onClick={query=""},modifier=Modifier.semantics { contentDescription=context.getString(R.string.core_clear_search) }) { Text("×",style=MaterialTheme.typography.titleMedium) } },
   keyboardOptions=KeyboardOptions(imeAction=ImeAction.Search),keyboardActions=KeyboardActions(onSearch={ saveSearch(query);focusManager.clearFocus() }))
  LazyColumn(Modifier.fillMaxSize(),state=rememberLazyListState(),contentPadding=PaddingValues(start=SpuntoSpacing.s20,end=SpuntoSpacing.s20,top=SpuntoSpacing.s8,bottom=SpuntoSpacing.s20)) {
   if(!online) item(key="offline") { Text(context.getString(R.string.core_offline_search),Modifier.padding(vertical=SpuntoSpacing.s8),style=MaterialTheme.typography.bodySmall,color=SpuntoColors.ink3) }
   if(online && query.trim().split(Regex("\\s+")).size>=3 && model.container.manifest.enabled("ask")) item(key="ask") { TextButton(enabled=!asking,onClick={asking=true; model.ask(query,onComplete={asking=false}) { if(model.online.value && model.container.manifest.enabled("ask")) answer=it.jsonObject }},modifier=Modifier.heightIn(min=48.dp)) { Text(if(asking) context.getString(R.string.core_reading_notes) else context.getString(R.string.core_ask_notes)) } }
   if(query.isEmpty() && recent.isNotEmpty()) {
    item(key="recent") {
     Row(Modifier.fillMaxWidth().padding(top=SpuntoSpacing.s8),verticalAlignment=Alignment.CenterVertically) {
      Text(context.getString(R.string.core_recent_searches).uppercase(),Modifier.weight(1f).semantics { heading();contentDescription=context.getString(R.string.core_recent_searches) },style=SpuntoType.overline,color=SpuntoColors.ink3)
      TextButton(onClick={ recent=emptyList();preferences.edit().remove("recent-searches").apply() },modifier=Modifier.heightIn(min=48.dp).semantics { contentDescription=context.getString(R.string.core_clear_recent) }) { Text(context.getString(R.string.core_clear)) }
     }
    }
    items(recent,key={ "recent:$it" }) { text -> Text(text,Modifier.fillMaxWidth().heightIn(min=48.dp).clickable(onClickLabel=context.getString(R.string.core_search_again)) { query=text;saveSearch(text) }.wrapContentHeight(Alignment.CenterVertically),style=MaterialTheme.typography.bodyLarge,maxLines=1,overflow=TextOverflow.Ellipsis) }
   }
   if(query.isEmpty() && labels.isNotEmpty()) item(key="labels") {
    // Before typing: the labels in most use, as shortcuts (L1).
    Column(Modifier.padding(vertical=SpuntoSpacing.s8),verticalArrangement=Arrangement.spacedBy(SpuntoSpacing.s4)) {
     Text(context.getString(R.string.core_labels).uppercase(),Modifier.semantics { heading();contentDescription=context.getString(R.string.core_labels) },style=SpuntoType.overline,color=SpuntoColors.ink3)
     FlowRow(horizontalArrangement=Arrangement.spacedBy(SpuntoSpacing.s8)) { labels.forEach { label -> SuggestionChip(onClick={query="#${label.path} "},label={ Text("#${label.path}") },modifier=Modifier.heightIn(min=48.dp)) } }
    }
   }
   items(results,key={ it.id }) { note -> SearchResult(note,terms,note.id in meaning) { saveSearch(query);onNote(note.id) } }
   if(hasMore || loadingPage) item(key="more") {TextButton(onClick={moreRequests++},enabled=!loadingPage,modifier=Modifier.heightIn(min=48.dp)) {Text(context.getString(if(loadingPage) R.string.core_loading else R.string.core_show_more))}}
   if(query.isNotBlank() && results.isEmpty()) item(key="none") {
    Column(Modifier.padding(vertical=SpuntoSpacing.s12),verticalArrangement=Arrangement.spacedBy(SpuntoSpacing.s4)) {
     Text(context.getString(R.string.core_search_empty,query.trim()),style=MaterialTheme.typography.bodyLarge)
     Text(context.getString(R.string.core_search_hint),style=MaterialTheme.typography.bodySmall,color=SpuntoColors.ink3)
    }
   }
  }
 }
 answer?.let { output -> ModalBottomSheet(onDismissRequest={answer=null},scrimColor=MaterialTheme.colorScheme.scrim) { Column(Modifier.padding(SpuntoSpacing.s20),verticalArrangement=Arrangement.spacedBy(SpuntoSpacing.s12)) { RenderOutput(model,JsonObject(output+mapOf("plugin_id" to JsonPrimitive("ask"))),onNote);TextButton(onClick={answer=null}) { Text(context.getString(R.string.core_close)) } } } }
}

/** One result (03 A7): the first line in the reading face, a snippet with the matches marked, then date and labels. */
@Composable private fun SearchResult(note:Note,terms:List<String>,similar:Boolean,onOpen:()->Unit) {
 val context=LocalContext.current
 val locale=androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
 val mark=SpanStyle(background=SpuntoColors.highlight,color=MaterialTheme.colorScheme.onSurface)
 Column(Modifier.fillMaxWidth().clickable(onClickLabel=context.getString(R.string.core_open)) { onOpen() }.padding(vertical=SpuntoSpacing.s12).semantics(mergeDescendants=true) {},verticalArrangement=Arrangement.spacedBy(SpuntoSpacing.s4)) {
  Text(marked(note.title.ifBlank { context.getString(R.string.core_untitled) },terms,mark),style=SpuntoType.reading,maxLines=2,overflow=TextOverflow.Ellipsis)
  searchSnippet(note.body,terms)?.let { Text(marked(it,terms,mark),style=SpuntoType.readingSmall,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=3,overflow=TextOverflow.Ellipsis) }
  Text(listOfNotNull(noteTime(note.createdAt,locale),labels(note.body).map { "#${it.path}" }.distinct().take(3).joinToString(" ").ifEmpty { null },context.getString(R.string.core_similar_meaning).takeIf { similar }).joinToString(" · "),style=MaterialTheme.typography.labelSmall,color=SpuntoColors.ink3)
 }
}
private fun marked(text:String,terms:List<String>,style:SpanStyle):AnnotatedString=buildAnnotatedString { append(text);matchRanges(text,terms).forEach { addStyle(style,it.first,it.last+1) } }

/** S2: the last [keep] searches, newest first; running one again moves it to the top. */
internal fun rememberSearch(recent:List<String>,query:String,keep:Int=5):List<String> = query.trim().takeIf { it.isNotEmpty() }?.let { text -> (listOf(text)+recent.filterNot { it.equals(text,ignoreCase=true) }).take(keep) } ?: recent
internal fun recentSearches(stored:String?):List<String> = stored?.let { runCatching { Json.parseToJsonElement(it).jsonArray.map { item -> item.jsonPrimitive.content } }.getOrNull() }.orEmpty()
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

/** Accumulated server relevance order and cursor for this query; local pending bodies are merged at presentation. */
internal data class SearchPages(val hits:List<Note> = emptyList(),val meaning:Set<String> = emptySet(),val cursor:String?=null) {
 fun append(page:JsonObject):SearchPages {
  val added=page.objects("hits")
  return SearchPages((hits+added.map(::noteFromWire)).distinctBy {it.id},meaning+added.filter {it.string("match")=="meaning"}.map {it.string("id")},page["next_cursor"]?.jsonPrimitive?.contentOrNull?.takeIf {it.isNotEmpty()})
 }
}

/** One cancelled query owns no later page publication, even if a transport completes after cancellation. */
internal suspend fun searchServerPages(request:suspend (String?)->JsonObject,awaitMore:suspend ()->Unit,show:(SearchPages)->Unit,failed:(Exception)->Unit) {
 var pages=SearchPages()
 while(true) {
  try {
   val response=request(pages.cursor)
   currentCoroutineContext().ensureActive()
   pages=pages.append(response);show(pages)
  } catch(cancelled:CancellationException) {throw cancelled}
  catch(error:Exception) {failed(error)}
  if(pages.cursor==null) return
  awaitMore()
 }
}
