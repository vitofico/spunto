package io.theficos.spunto.core.design

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.ClickableText
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.commonmark.node.*
import org.commonmark.parser.Parser
import org.commonmark.parser.IncludeSourceSpans

private val parser=Parser.builder().includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES).build()
private val taskMarker=Regex("^\\s*(?:[-*+]|[0-9]+[.)]) \\[([ xX])] ")
private val attachmentTarget=Regex("^/a/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
private val recordingLabel=Regex("^Recording [0-9]+:[0-5][0-9]$")
/** Parsed recording labels and relative targets, in source order; never scan code or images. */
fun recordingAttachmentLinks(body:String):List<Pair<String,String>> {
 val result=mutableListOf<Pair<String,String>>()
 fun walk(node:Node) {
  if(node is Image || node is Code || node is FencedCodeBlock || node is IndentedCodeBlock || node is HtmlBlock || node is HtmlInline) return
  if(node is Link) {
   val label=plain(node)
   if(recordingLabel.matches(label) && attachmentTarget.matches(node.destination)) result.add(label to node.destination)
  }
  var child=node.firstChild;while(child!=null) { walk(child);child=child.next }
 }
 walk(parser.parse(body));return result
}
internal fun internalMarkdownTarget(target:String):Boolean = target.startsWith("/n/") || attachmentTarget.matches(target)
/** Only real list items may become checkboxes; code blocks retain their literal text. */
internal fun checklistLines(body:String):List<Int> {
 val lines=body.lines();val result=mutableListOf<Int>()
 fun walk(node:Node) {
  if(node is ListItem && node.firstChild is org.commonmark.node.Paragraph) {
   node.sourceSpans.firstOrNull()?.lineIndex?.takeIf { it<lines.size && taskMarker.containsMatchIn(lines[it]) }?.let(result::add)
  }
  var child=node.firstChild;while(child!=null) { walk(child);child=child.next }
 }
 walk(parser.parse(body));return result
}
/** Matches the server's paragraph checkbox counts while excluding parsed inline/block code. */
fun hasOpenTasks(body:String):Boolean {
 val lines=body.lines();var found=false
 fun walk(node:Node) {
  if(node is org.commonmark.node.Paragraph) {
   val code=mutableListOf<SourceSpan>()
   fun codeSpans(child:Node) { if(child is Code) code.addAll(child.sourceSpans);var nested=child.firstChild;while(nested!=null) { codeSpans(nested);nested=nested.next } }
   codeSpans(node)
   node.sourceSpans.forEach { span ->
    val raw=lines.getOrNull(span.lineIndex).orEmpty().drop(span.columnIndex).take(span.length)
    Regex("^\\s*\\[ ]\\s").find(raw)?.let { marker ->
     val column=span.columnIndex+raw.indexOf('[')
     if(code.none { it.lineIndex==span.lineIndex && column>=it.columnIndex && column<it.columnIndex+it.length }) found=true
    }
   }
  }
  var child=node.firstChild;while(child!=null) { walk(child);child=child.next }
 }
 walk(parser.parse(body));return found
}
/** Native text renderer; raw HTML is never executed. */
@Composable fun Markdown(body:String, machine:Boolean=false, onLink:((String)->Unit)?=null, onToggle: ((Int)->Unit)? = null) {
 val document=remember(body) { parser.parse(body) }
 val tasks=remember(body) { checklistLines(body).toSet() }
 Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
  var node=document.firstChild
  while(node!=null) { MarkdownBlock(node,body,tasks,machine,onLink,onToggle);node=node.next }
 }
}
internal fun markdownBodyStyle(machine:Boolean,ownerStyle:TextStyle):TextStyle =
 if(machine) ownerStyle.copy(fontFamily=uiFont(),fontSize=17.sp,lineHeight=26.sp) else ownerStyle
@Composable private fun MarkdownText(text:AnnotatedString,machine:Boolean,onLink:((String)->Unit)?) {
 val uri=LocalUriHandler.current
 ClickableText(text,style=markdownBodyStyle(machine,SpuntoType.reading).copy(color=MaterialTheme.colorScheme.onSurface),onClick={position -> text.getStringAnnotations("URL",position,position).firstOrNull()?.item?.let { target -> if(internalMarkdownTarget(target)) onLink?.invoke(target) else if(target.startsWith("https://") || target.startsWith("http://")) runCatching { uri.openUri(target) } } })
}
@Composable private fun MarkdownBlock(current:Node,body:String,tasks:Set<Int>,machine:Boolean,onLink:((String)->Unit)?,onToggle:((Int)->Unit)?) {
 when(current) {
  is Heading -> Text(inline(current),style=if(current.level<=2) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium)
  is FencedCodeBlock -> Surface(color=MaterialTheme.colorScheme.surfaceVariant) { Text(current.literal.trimEnd(),Modifier.padding(12.dp),fontFamily=FontFamily.Monospace) }
  is IndentedCodeBlock -> Text(current.literal.trimEnd(),fontFamily=FontFamily.Monospace)
  is ThematicBreak -> HorizontalDivider()
  is HtmlBlock -> Unit
  is BulletList,is OrderedList -> Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
   var item=current.firstChild;var number=if(current is OrderedList) (current.markerStartNumber ?: 1) else 1
   while(item!=null) {
    val line=item.sourceSpans.firstOrNull()?.lineIndex
    val paragraph=item.firstChild
    Row {
     if(line!=null && line in tasks) {
      Checkbox(checked=taskMarker.find(body.lines()[line])?.groupValues?.get(1)?.equals("x",true)==true,onCheckedChange=onToggle?.let { { _:Boolean -> it(line) } })
      val text=inline(requireNotNull(paragraph));MarkdownText(text.subSequence(4.coerceAtMost(text.length),text.length),machine,onLink)
     } else {
      Text(if(current is OrderedList) "${number++}. " else "• ")
      if(paragraph!=null) MarkdownText(inline(paragraph),machine,onLink)
     }
    }
    var extra=paragraph?.next
    while(extra!=null) { val child=extra;Box(Modifier.padding(start=20.dp)) { MarkdownBlock(child,body,tasks,machine,onLink,onToggle) };extra=child.next }
    item=item.next
   }
  }
  is BlockQuote -> Column(Modifier.padding(start=16.dp)) { var child=current.firstChild;while(child!=null) { MarkdownBlock(child,body,tasks,machine,onLink,onToggle);child=child.next } }
  else -> MarkdownText(inline(current),machine,onLink)
 }
}
private fun plain(node: Node):String = when(node) { is org.commonmark.node.Text -> node.literal; is Code -> node.literal; is SoftLineBreak,is HardLineBreak -> "\n"; else -> buildString { var child=node.firstChild; while(child!=null) { append(plain(child)); child=child.next } } }
private fun inline(node:Node):AnnotatedString=buildAnnotatedString {
 fun walk(n:Node) {
  when(n) {
   is org.commonmark.node.Text -> append(n.literal)
   is SoftLineBreak,is HardLineBreak -> append("\n")
   is Code -> withStyle(SpanStyle(fontFamily=FontFamily.Monospace)) { append(n.literal) }
   is Emphasis -> withStyle(SpanStyle(fontStyle=FontStyle.Italic)) { var child=n.firstChild; while(child!=null) { walk(child); child=child.next } }
   is StrongEmphasis -> withStyle(SpanStyle(fontWeight=FontWeight.SemiBold)) { var child=n.firstChild; while(child!=null) { walk(child); child=child.next } }
   is Link -> { pushStringAnnotation("URL",n.destination); withStyle(SpanStyle(textDecoration=androidx.compose.ui.text.style.TextDecoration.Underline)) { var child=n.firstChild; while(child!=null) { walk(child); child=child.next } }; pop() }
   is HtmlBlock,is HtmlInline -> Unit
   is Image -> append("[Image: ${plain(n)}]")
   else -> { var child=n.firstChild; while(child!=null) { walk(child); child=child.next } }
  }
 }
 walk(node)
}
