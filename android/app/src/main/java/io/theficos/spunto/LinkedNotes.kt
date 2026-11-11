package io.theficos.spunto

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.theficos.spunto.core.design.*
import io.theficos.spunto.core.model.*
import io.theficos.spunto.core.plugins.*
import io.theficos.spunto.data.local.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.serialization.json.*

private const val FIRST=5
/** The notes the Links plugin recorded as pointing at this one. */
internal fun backlinkIds(outputs:List<PluginOutputEntity>,noteId:String):List<String> =
 outputs.filter {it.noteId==noteId && it.kind=="annotation"}.map {payload(it.payload)}
  .filter {it.string("plugin").ifEmpty {it.string("plugin_id")}=="links" && it.string("kind")=="panel"}
  .flatMap(::outputComponents).filter {it.string("type")=="notes"}
  .flatMap {((it["ids"] ?: it["items"]) as? JsonArray).orEmpty()}
  .mapNotNull {(it as? JsonObject)?.string("id") ?: (it as? JsonPrimitive)?.content}.distinct()
/** One line of what a note says after its title, without the Markdown marks. */
internal fun noteExcerpt(body:String):String = titleOf(body.lines().dropWhile(String::isBlank).drop(1).joinToString("\n"),160)

/** Both directions in one place: the notes this one points at, and the notes that point here. A note that is not on this phone has no row. */
@Composable internal fun LinkedNotes(to:List<Note>,from:List<Note>,locale:Locale,onNote:(String)->Unit) {
 if(to.isEmpty() && from.isEmpty()) return
 val context=LocalContext.current
 Column {
  HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
  Text(context.getString(R.string.core_linked_notes),Modifier.padding(top=SpuntoSpacing.s16).semantics {heading()},style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
  LinkedGroup(context.getString(R.string.core_links_to),to,locale,onNote)
  LinkedGroup(context.getString(R.string.core_linked_from),from,locale,onNote)
 }
}
@Composable private fun LinkedGroup(title:String,notes:List<Note>,locale:Locale,onNote:(String)->Unit) {
 if(notes.isEmpty()) return
 val context=LocalContext.current
 var all by remember {mutableStateOf(false)}
 val day=remember(locale) {DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)}
 Text(title.uppercase(locale),Modifier.padding(top=SpuntoSpacing.s16,bottom=SpuntoSpacing.s4).semantics {heading()},style=SpuntoType.overline,color=SpuntoColors.ink3)
 (if(all) notes else notes.take(FIRST)).forEachIndexed {index,note ->
  if(index>0) HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
  Column(Modifier.fillMaxWidth().heightIn(min=48.dp).clickable(role=Role.Button) {onNote(note.id)}.padding(vertical=SpuntoSpacing.s8),verticalArrangement=Arrangement.Center) {
   Row(horizontalArrangement=Arrangement.spacedBy(SpuntoSpacing.s12)) {
    Text(note.title.ifBlank {context.getString(R.string.core_untitled)},Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium.copy(fontWeight=FontWeight.Medium),maxLines=1,overflow=TextOverflow.Ellipsis)
    Text(Instant.ofEpochMilli(note.updatedAt).atZone(ZoneId.systemDefault()).format(day),style=MaterialTheme.typography.bodySmall,color=SpuntoColors.ink3)
   }
   noteExcerpt(note.body).takeIf(String::isNotBlank)?.let {Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)}
  }
 }
 if(!all && notes.size>FIRST) TextButton(onClick={all=true},contentPadding=PaddingValues(0.dp),modifier=Modifier.heightIn(min=48.dp)) {Text(context.getString(R.string.core_show_all,notes.size),style=MaterialTheme.typography.bodySmall,color=SpuntoColors.ink3)}
}
