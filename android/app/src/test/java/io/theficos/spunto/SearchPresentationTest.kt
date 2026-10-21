package io.theficos.spunto

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
 @Test fun recentSearchesKeepTheLastFiveNewestFirstWithoutRepeats() {
  var recent=listOf<String>()
  listOf("garden","  ","#idea ","bread","Garden","one","two","three").forEach { recent=rememberSearch(recent,it) }
  assertEquals(listOf("three","two","one","Garden","bread"),recent)
  assertEquals(recent,recentSearches("""["three","two","one","Garden","bread"]"""))
  assertEquals(emptyList<String>(),recentSearches(null));assertEquals(emptyList<String>(),recentSearches("not json"))
 }
}
