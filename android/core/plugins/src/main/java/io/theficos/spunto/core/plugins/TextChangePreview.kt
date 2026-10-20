package io.theficos.spunto.core.plugins

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
  if(changes.none {it.kind!=TextChangeKind.Unchanged}) Text("No wording changes.")
  else {
   Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
    Surface(color=MaterialTheme.colorScheme.errorContainer,shape=MaterialTheme.shapes.small) {Text("− Removed",Modifier.padding(horizontal=8.dp,vertical=4.dp),color=MaterialTheme.colorScheme.onErrorContainer,style=MaterialTheme.typography.labelMedium)}
    Surface(color=MaterialTheme.colorScheme.primaryContainer,shape=MaterialTheme.shapes.small) {Text("+ Added",Modifier.padding(horizontal=8.dp,vertical=4.dp),color=MaterialTheme.colorScheme.onPrimaryContainer,style=MaterialTheme.typography.labelMedium)}
   }
   val whitespace=remember(changes) {whitespaceChangeSummaries(changes)}
   if(whitespace.isNotEmpty()) Text(whitespace.joinToString(" · "),style=MaterialTheme.typography.bodySmall,color=colors.onSurfaceVariant)
   val display=if(full) changes else focusedTextChanges(changes)
   SelectionContainer {Text(buildAnnotatedString {
    display.forEach {change -> when(change.kind) {
     TextChangeKind.Unchanged -> append(change.text)
     TextChangeKind.Removed -> withStyle(SpanStyle(color=colors.onErrorContainer,background=colors.errorContainer,textDecoration=TextDecoration.LineThrough)) {append(change.text)}
     TextChangeKind.Added -> withStyle(SpanStyle(color=colors.onPrimaryContainer,background=colors.primaryContainer)) {append(change.text)}
    }}
   })}
   if(allowFullText) {
    TextButton(onClick={full=!full}) {Text(if(full) "Show changes only" else "Show full comparison")}
    if(full) {Text("Proposed text",style=MaterialTheme.typography.titleSmall);SelectionContainer {Text(after)}}
   }
  }
 }
}
