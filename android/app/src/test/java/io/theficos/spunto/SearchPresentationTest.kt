package io.theficos.spunto

import io.theficos.spunto.core.model.LabelCount
import org.junit.Assert.*
import org.junit.Test

class SearchPresentationTest {
 @Test fun termsLeaveOutLabelFiltersAndMatchFoldedWordStarts() {
  val terms=searchTerms("#project Perche straße")
  assertEquals(listOf("perche","strasse"),terms)
  val text="Perché la Straße? Ripercorrere"
  assertEquals(listOf(0..5,10..15),matchRanges(text,terms))
  assertEquals(emptyList<IntRange>(),matchRanges("Ripercorrere",listOf("perc")))
 }
 @Test fun snippetIsTheMatchingLineAfterTheTitleCutAroundTheMatch() {
  val body="Title line\n- [ ] buy bread\n\nThe garden needs water"
  assertEquals("The garden needs water",searchSnippet(body,listOf("garden")))
  assertEquals("buy bread",searchSnippet(body,listOf("nothing")))
  assertNull(searchSnippet("Only a title",listOf("title")))
  val long="Title\n"+"a ".repeat(100)+"needle "+"b ".repeat(100)
  val snippet=searchSnippet(long,listOf("needle"),width=60)!!
  assertTrue(snippet.startsWith("…") && snippet.endsWith("…") && "needle" in snippet)
 }
 @Test fun topLabelsAreTheMostUsed() {
  val labels=listOf(LabelCount("a","a",1),LabelCount("b","b",9),LabelCount("c","c",4))
  assertEquals(listOf("b","c"),topLabels(labels,2).map { it.path })
 }
}
