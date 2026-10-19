package io.theficos.spunto

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import org.junit.Assert.*
import org.junit.Test

class MarkdownEditorHighlightTest {
 @Test fun decorationKeepsEverySourceOffsetAndIncomingAnnotationAcrossUnicodeAndCrLf() {
  val body="# È heading\r\nWords 🙂 **strong** #project/spunto `#not-label`"
  val input=AnnotatedString(body,listOf(AnnotatedString.Range(SpanStyle(color=Color.Green),0,1)))
  val transformed=MarkdownEditorHighlight(Color.Red,Color.LightGray).filter(input)
  assertEquals(body,transformed.text.text)
  assertTrue(transformed.text.spanStyles.contains(input.spanStyles.single()))
  for(offset in 0..body.length) {
   assertEquals(offset,transformed.offsetMapping.originalToTransformed(offset))
   assertEquals(offset,transformed.offsetMapping.transformedToOriginal(offset))
  }
  val label=transformed.text.spanStyles.single {it.item.color==Color.Red}
  assertEquals("#project/spunto",body.substring(label.start,label.end))
  assertTrue(transformed.text.spanStyles.any {it.item.fontFamily==FontFamily.Monospace && body.substring(it.start,it.end)=="`#not-label`"})
 }
 @Test fun liveRecognitionColourWinsOnlyItsExactRangeAndNeverChangesSource() {
  val body="# Heading\nnew words #label"
  val start=body.indexOf("new")
  val transformed=MarkdownEditorHighlight(Color.Red,Color.LightGray,start..start+8,Color.Blue).filter(AnnotatedString(body))
  assertEquals(body,transformed.text.text)
  val partial=transformed.text.spanStyles.last()
  assertEquals(Color.Blue,partial.item.color)
  assertEquals("new words",body.substring(partial.start,partial.end))
  assertFalse(MarkdownEditorHighlight(Color.Red,Color.LightGray,-1..4,Color.Blue).filter(AnnotatedString(body)).text.spanStyles.any {it.item.color==Color.Blue})
 }
}
