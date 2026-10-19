package io.theficos.spunto

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import io.theficos.spunto.core.design.MarkdownSourceKind
import io.theficos.spunto.core.design.markdownSourceSpans

/** Source-only decoration: identity offsets preserve the editor's caret and IME composition. */
internal class MarkdownEditorHighlight(
 private val labelColor:Color,
 private val codeBackground:Color,
 private val partialSpan:IntRange?=null,
 private val partialColor:Color=labelColor,
):VisualTransformation {
 override fun filter(text:AnnotatedString):TransformedText {
  val rendered=buildAnnotatedString {
   append(text)
   markdownSourceSpans(text.text).forEach {span ->
    val style=when(span.kind) {
     MarkdownSourceKind.Heading,MarkdownSourceKind.Strong -> SpanStyle(fontWeight=FontWeight.SemiBold)
     MarkdownSourceKind.Emphasis -> SpanStyle(fontStyle=FontStyle.Italic)
     MarkdownSourceKind.Code -> SpanStyle(fontFamily=FontFamily.Monospace,background=codeBackground)
    }
    addStyle(style,span.start,span.end)
   }
   io.theficos.spunto.core.model.labels(text.text).forEach {addStyle(SpanStyle(color=labelColor),it.start,it.end)}
   partialSpan?.takeIf {it.first>=0 && it.last<text.length}?.let {addStyle(SpanStyle(color=partialColor),it.first,it.last+1)}
  }
  return TransformedText(rendered,OffsetMapping.Identity)
 }
}
