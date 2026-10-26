package io.theficos.spunto.core.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import org.commonmark.node.*
import org.commonmark.parser.Parser
import org.commonmark.parser.IncludeSourceSpans

val LocalCompactMarkdown=compositionLocalOf {false}
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
/** Native text renderer; raw HTML is never executed. Taps outside links and labels fall through to the caller (open or edit the note); [onText] hears where in the source a finger lands on the text first (null when unmapped). */
@Composable fun Markdown(body:String, machine:Boolean=false, onLink:((String)->Unit)?=null, onLabel:((String)->Unit)?=null, onText:((Int?)->Unit)?=null, maxLines:Int?=null, onToggle: ((Int)->Unit)? = null) {
 val document=remember(body) { parser.parse(body) }
 val tasks=remember(body) { checklistLines(body).toSet() }
 val uri=LocalUriHandler.current
 val links=MarkdownLinks(MaterialTheme.colorScheme.primary,SpuntoColors.ruleStrong,{ target -> if(internalMarkdownTarget(target)) onLink?.invoke(target) else if(target.startsWith("https://") || target.startsWith("http://")) runCatching { uri.openUri(target) } },onLabel,onText,body)
 if(maxLines!=null) {
  val resources=LocalContext.current.resources
  val excerpt=inline(document,links) {alt -> resources.getString(R.string.design_image,alt)}
  Text(excerpt,style=markdownBodyStyle(machine,SpuntoType.reading).copy(color=MaterialTheme.colorScheme.onSurface),maxLines=maxLines,overflow=TextOverflow.Ellipsis)
  return
 }
 Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
  var node=document.firstChild
  while(node!=null) { MarkdownBlock(node,body,tasks,machine,links,onToggle);node=node.next }
 }
}
internal fun markdownBodyStyle(machine:Boolean,ownerStyle:TextStyle):TextStyle =
 if(machine) ownerStyle.copy(fontFamily=uiFont(),fontSize=17.sp,lineHeight=26.sp) else ownerStyle
/** Labels are accent and filter by that label; links are ink with a rule-strong underline (03 §5.1), so the two stay distinct. */
internal class MarkdownLinks(val label:Color,val underline:Color,val open:(String)->Unit,val onLabel:((String)->Unit)?,val onText:((Int?)->Unit)?=null,val body:String="") {
 /** Maps a tap in [node]'s rendered text to the source, read from its [line]-th source line to its end. */
 fun tap(node:Node,line:Int=0):((CharSequence,Int)->Unit)? {
  val report=onText ?: return null;val start=node.sourceSpans.getOrNull(line)?.inputIndex ?: return null
  val end=node.sourceSpans.last().let { it.inputIndex+it.length }
  return { text,offset -> report(sourceOffset(body,start,end,text,offset)) }
 }
}
/** AN-15: rendered text is the source minus its markup, so walk the source in order, as the web editor does; null when the text does not follow the source (an image caption). */
internal fun sourceOffset(body:String,start:Int,end:Int,rendered:CharSequence,offset:Int):Int? {
 var at=start
 for(index in 0 until offset.coerceIn(0,rendered.length)) { val found=body.indexOf(rendered[index],at);if(found<0 || found>=end) return null;at=found+1 }
 // Just before the tapped character, past any markup in front of it.
 return rendered.getOrNull(offset)?.let { next -> body.indexOf(next,at).takeIf { it in at until end } } ?: at
}
/** Reports where a finger lands in the text without taking the tap: links, checkboxes and the caller's edit-in-place still get it. */
@Composable private fun TappableText(text:AnnotatedString,style:TextStyle,tap:((CharSequence,Int)->Unit)?,modifier:Modifier=Modifier) {
 var layout by remember { mutableStateOf<TextLayoutResult?>(null) };val current by rememberUpdatedState(tap)
 Text(text,if(tap==null) modifier else modifier.pointerInput(Unit) { awaitEachGesture { val down=awaitFirstDown(requireUnconsumed=false);layout?.let { current?.invoke(it.layoutInput.text,it.getOffsetForPosition(down.position)) } } },style=style,onTextLayout={ layout=it })
}
@Composable private fun MarkdownText(text:AnnotatedString,machine:Boolean,color:Color=MaterialTheme.colorScheme.onSurface,modifier:Modifier=Modifier,tap:((CharSequence,Int)->Unit)?=null) {
 TappableText(text,markdownBodyStyle(machine,SpuntoType.reading).copy(color=color),tap,modifier)
}
@Composable private fun MarkdownBlock(current:Node,body:String,tasks:Set<Int>,machine:Boolean,links:MarkdownLinks,onToggle:((Int)->Unit)?) {
 val resources=LocalContext.current.resources
 fun renderInline(node:Node)=inline(node,links) {alt -> resources.getString(R.string.design_image,alt)}
 when(current) {
  is Heading -> TappableText(renderInline(current),if(current.level<=2) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,links.tap(current))
  is FencedCodeBlock -> Surface(color=MaterialTheme.colorScheme.surfaceVariant,contentColor=MaterialTheme.colorScheme.onSurface,shape=MaterialTheme.shapes.small) { TappableText(AnnotatedString(current.literal.trimEnd()),SpuntoType.mono,links.tap(current,line=1),Modifier.padding(SpuntoSpacing.s12)) }
  is IndentedCodeBlock -> TappableText(AnnotatedString(current.literal.trimEnd()),SpuntoType.mono,links.tap(current))
  is ThematicBreak -> HorizontalDivider()
  is HtmlBlock -> Unit
  is BulletList,is OrderedList -> Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
   var item=current.firstChild;var number=if(current is OrderedList) (current.markerStartNumber ?: 1) else 1
   while(item!=null) {
    val line=item.sourceSpans.firstOrNull()?.lineIndex
    val paragraph=item.firstChild
    if(line!=null && line in tasks) {
     val checked=taskMarker.find(body.lines()[line])?.groupValues?.get(1)?.equals("x",true)==true
     val text=renderInline(requireNotNull(paragraph)).let { it.subSequence(4.coerceAtMost(it.length),it.length) }
     // The whole row is one checkbox for touch and TalkBack; the box itself takes no clicks of its own.
     Row(Modifier.fillMaxWidth().heightIn(min=48.dp).toggleable(checked,enabled=onToggle!=null,role=Role.Checkbox) { onToggle?.invoke(line) },horizontalArrangement=Arrangement.spacedBy(SpuntoSpacing.s12),verticalAlignment=Alignment.CenterVertically) {
      if(LocalCompactMarkdown.current) Box(Modifier.size(20.dp).border(1.dp,MaterialTheme.colorScheme.onSurfaceVariant,CircleShape).background(if(checked) MaterialTheme.colorScheme.primary else Color.Transparent,CircleShape).clearAndSetSemantics {},contentAlignment=Alignment.Center) {if(checked) Text("✓",color=MaterialTheme.colorScheme.onPrimary,style=MaterialTheme.typography.labelSmall)} else Checkbox(checked=checked,onCheckedChange=null)
      MarkdownText(text,machine,if(checked) SpuntoColors.ink3 else MaterialTheme.colorScheme.onSurface,Modifier.weight(1f))
     }
    } else Row {
     Text(if(current is OrderedList) "${number++}." else "•",Modifier.width(24.dp),style=markdownBodyStyle(machine,SpuntoType.reading),color=SpuntoColors.ink3)
     if(paragraph!=null) MarkdownText(renderInline(paragraph),machine,modifier=Modifier.weight(1f),tap=links.tap(paragraph))
    }
    var extra=paragraph?.next
    while(extra!=null) { val child=extra;Box(Modifier.padding(start=SpuntoSpacing.s24)) { MarkdownBlock(child,body,tasks,machine,links,onToggle) };extra=child.next }
    item=item.next
   }
  }
  is BlockQuote -> Row(Modifier.height(IntrinsicSize.Min)) {
   Box(Modifier.width(3.dp).fillMaxHeight().background(SpuntoColors.ruleStrong))
   Column(Modifier.padding(start=13.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) { var child=current.firstChild;while(child!=null) { MarkdownBlock(child,body,tasks,machine,links,onToggle);child=child.next } }
  }
  else -> MarkdownText(renderInline(current),machine,tap=links.tap(current))
 }
}
private fun plain(node: Node):String = when(node) { is org.commonmark.node.Text -> node.literal; is Code -> node.literal; is SoftLineBreak,is HardLineBreak -> "\n"; else -> buildString { var child=node.firstChild; while(child!=null) { append(plain(child)); child=child.next } } }
/** Inline #labels, as the core tokenizer reads them outside code: a letter- or digit-led path of up to five segments after a space or bracket. */
private val inlineLabel=Regex("(?<=^|[\\s(\\[])#([\\p{L}\\p{N}\\p{M}_-]+(?:/[\\p{L}\\p{N}\\p{M}_-]+){0,4})(?![\\p{L}\\p{N}\\p{M}_/-])")
internal fun inlineLabels(text:String):List<IntRange> = inlineLabel.findAll(text).filterNot { match -> match.groupValues[1].substringBefore('/').all(Char::isDigit) }.map { it.range }.toList()
private fun inline(node:Node,links:MarkdownLinks?,imageDescription:(String)->String):AnnotatedString {
 val code=mutableListOf<IntRange>()
 val builder=AnnotatedString.Builder()
 fun walk(n:Node) {
  fun children() { var child=n.firstChild; while(child!=null) { walk(child); child=child.next } }
  when(n) {
   is org.commonmark.node.Text -> builder.append(n.literal)
   is SoftLineBreak,is HardLineBreak -> builder.append("\n")
   is Code -> { val start=builder.length;builder.withStyle(SpanStyle(fontFamily=monoFont(),fontSize=0.88.em)) { append(n.literal) };code+=start until builder.length }
   is Emphasis -> builder.withStyle(SpanStyle(fontStyle=FontStyle.Italic)) { children() }
   is StrongEmphasis -> builder.withStyle(SpanStyle(fontWeight=FontWeight.SemiBold)) { children() }
   is Link -> {
    val style=TextLinkStyles(SpanStyle(textDecoration=TextDecoration.Underline,color=Color.Unspecified))
    val target=n.destination
    if(links!=null && (internalMarkdownTarget(target) || target.startsWith("https://") || target.startsWith("http://"))) builder.withLink(LinkAnnotation.Clickable(target,style) { links.open(target) }) { children() }
    else builder.withStyle(SpanStyle(textDecoration=TextDecoration.Underline)) { children() }
   }
   is HtmlBlock,is HtmlInline -> Unit
   is org.commonmark.node.Paragraph,is Heading -> {if(builder.length>0) builder.append("\n");children()}
   is FencedCodeBlock -> {if(builder.length>0) builder.append("\n");val start=builder.length;builder.append(n.literal.trimEnd());code+=start until builder.length}
   is IndentedCodeBlock -> {if(builder.length>0) builder.append("\n");val start=builder.length;builder.append(n.literal.trimEnd());code+=start until builder.length}
   is Image -> builder.append(imageDescription(plain(n)))
   else -> children()
  }
 }
 walk(node)
 if(links!=null) {
  val text=builder.toAnnotatedString().text
  inlineLabels(text).filter { label -> code.none { it.first<=label.first && label.first<=it.last } }.forEach { range ->
   val path=text.substring(range.first+1,range.last+1)
   val style=SpanStyle(color=links.label)
   val onLabel=links.onLabel
   if(onLabel!=null) builder.addLink(LinkAnnotation.Clickable("label:$path",TextLinkStyles(style)) { onLabel(path) },range.first,range.last+1)
   else builder.addStyle(style,range.first,range.last+1)
  }
 }
 return builder.toAnnotatedString()
}
