package io.theficos.spunto.core.plugins

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

@Composable fun TextChangePreview(before:String,after:String,allowFullText:Boolean=true) {
 val changes=remember(before,after) {textChanges(before,after)}
 var full by remember(before,after) {mutableStateOf(false)}
 val colors=MaterialTheme.colorScheme
 Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
  if(changes.none {it.kind!=TextChangeKind.Unchanged}) Text(stringResource(R.string.plugin_ui_no_changes))
  else {
   Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
    Surface(color=MaterialTheme.colorScheme.errorContainer,shape=MaterialTheme.shapes.small) {Text(stringResource(R.string.plugin_ui_removed),Modifier.padding(horizontal=8.dp,vertical=4.dp),color=MaterialTheme.colorScheme.onErrorContainer,style=MaterialTheme.typography.labelMedium)}
    Surface(color=MaterialTheme.colorScheme.primaryContainer,shape=MaterialTheme.shapes.small) {Text(stringResource(R.string.plugin_ui_added),Modifier.padding(horizontal=8.dp,vertical=4.dp),color=MaterialTheme.colorScheme.onPrimaryContainer,style=MaterialTheme.typography.labelMedium)}
   }
   WhitespaceSummary(changes)
   val display=if(full) changes else focusedTextChanges(changes,stringResource(R.string.plugin_ui_unchanged))
   SelectionContainer {Text(buildAnnotatedString {
    display.forEach {change -> when(change.kind) {
     TextChangeKind.Unchanged -> append(change.text)
     TextChangeKind.Removed -> withStyle(SpanStyle(color=colors.onErrorContainer,background=colors.errorContainer,textDecoration=TextDecoration.LineThrough)) {append(change.text)}
     TextChangeKind.Added -> withStyle(SpanStyle(color=colors.onPrimaryContainer,background=colors.primaryContainer)) {append(change.text)}
    }}
   })}
   if(allowFullText) {
    TextButton(onClick={full=!full}) {Text(if(full) stringResource(R.string.plugin_ui_changes_only) else stringResource(R.string.plugin_ui_comparison))}
    if(full) {Text(stringResource(R.string.plugin_ui_proposed_text),style=MaterialTheme.typography.titleSmall);SelectionContainer {Text(after)}}
   }
  }
 }
}

@Composable fun RevisionChangePreview(before: String, after: String) {
 val changes=remember(before,after) {textChanges(before,after)}
 if(before==after) {Text(stringResource(R.string.plugin_ui_no_changes));return}
 val focused=remember(changes) {revisionPassageContext(changes)}
 val canExpand=focused!=changes || before.length>360 || after.length>360
 var full by remember(before,after) {mutableStateOf(false)}
 val colors=MaterialTheme.colorScheme
 Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
  if(full) TextButton(onClick={full=false}) {Text(stringResource(R.string.plugin_ui_changes_only))}
  for(kind in listOf(TextChangeKind.Removed,TextChangeKind.Added)) {
   val removed=kind==TextChangeKind.Removed
   val display=remember(full,changes,focused,kind,colors) {
    var remaining=if(full) Int.MAX_VALUE else 360
    buildAnnotatedString {
     for(change in (if(full) changes else focused).filter {it.kind==kind || it.kind==TextChangeKind.Unchanged}) {
      val limit=minOf(remaining,change.text.length)
      val end=if(limit<change.text.length && limit>0 && change.text[limit-1].isHighSurrogate()) limit-1 else limit
      val text=change.text.substring(0,end)
      val mark=if(removed) SpanStyle(color=colors.onErrorContainer,background=colors.errorContainer,textDecoration=TextDecoration.LineThrough)
       else SpanStyle(color=colors.onPrimaryContainer,background=colors.primaryContainer)
      if(change.kind==kind) withStyle(mark) {append(text)} else append(text)
      if(end<change.text.length) {append("…");break}
      remaining-=end
     }
    }
   }
   Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
    Text(stringResource(if(removed) R.string.plugin_ui_before else R.string.plugin_ui_after),
     modifier=Modifier.semantics {heading()},style=MaterialTheme.typography.labelLarge)
    if((if(removed) before else after).isEmpty()) Text(stringResource(R.string.plugin_ui_empty_text),style=MaterialTheme.typography.bodyMedium,color=colors.onSurfaceVariant)
    else SelectionContainer {Text(display,style=MaterialTheme.typography.bodyMedium)}
   }
  }
  WhitespaceSummary(changes)
  if(canExpand && !full) TextButton(onClick={full=true}) {Text(stringResource(R.string.plugin_ui_comparison))}
 }
}

/** Keep the changed passages and the nearest line of unchanged text on either end. */
private fun revisionPassageContext(changes: List<TextChange>): List<TextChange> =
 focusedTextChanges(changes).mapIndexed {index, change ->
  if(change.kind!=TextChangeKind.Unchanged) change else {
   val text=when(index) {
    0 -> change.text.substringAfterLast('\n').let {if(it!=change.text) "… $it" else it}
    changes.lastIndex -> change.text.substringBefore('\n').let {if(it!=change.text) "$it …" else it}
    else -> change.text
   }
   change.copy(text=text)
  }
 }

@Composable private fun WhitespaceSummary(changes: List<TextChange>) {
 val whitespace=remember(changes) {whitespaceChanges(changes)}.map {change ->
  val added=change.kind==TextChangeKind.Added
  val label=when(change.unit) {
   WhitespaceUnit.Break -> if(added) R.plurals.plugin_ui_added_break else R.plurals.plugin_ui_removed_break
   WhitespaceUnit.Space -> if(added) R.plurals.plugin_ui_added_space else R.plurals.plugin_ui_removed_space
   WhitespaceUnit.Tab -> if(added) R.plurals.plugin_ui_added_tab else R.plurals.plugin_ui_removed_tab
  }
  pluralStringResource(label,change.count,change.count)
 }
 if(whitespace.isNotEmpty()) Text(whitespace.joinToString(" · "),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
}
