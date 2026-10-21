package io.theficos.spunto

import androidx.compose.runtime.mutableStateListOf
import org.junit.Assert.*
import org.junit.Test

class NavigatorTest {
 private val forgotten=mutableListOf<String>()
 private fun navigator(vararg routes:String)=Navigator(mutableStateListOf(*routes),forgotten::add)

 @Test fun backReturnsToWherePagesWereOpenedAndForgetsTheirState() {
  val nav=navigator("timeline")
  nav.open("search");nav.open("note:a");nav.open("note:b")
  nav.back();assertEquals("note:a",nav.route)
  nav.back();assertEquals("search",nav.route)
  nav.back();assertEquals("timeline",nav.route);assertFalse(nav.canGoBack)
  nav.back();assertEquals("timeline",nav.route)
  assertEquals(listOf("3:note:b","2:note:a","1:search"),forgotten)
 }
 @Test fun reopeningTheCurrentPageDoesNotStackIt() {
  val nav=navigator("timeline","settings")
  nav.open("settings");nav.back()
  assertEquals("timeline",nav.route)
 }
 @Test fun filtersReplaceEachOtherAboveAllNotes() {
  val nav=navigator("timeline")
  nav.filter("label:idea");assertEquals("1:label:idea",nav.stateKey)
  nav.filter("archive");nav.open("note:a")
  nav.back();assertEquals("archive",nav.route)
  nav.back();assertEquals("timeline",nav.route)
  assertTrue("1:label:idea" in forgotten)
  nav.filter("timeline");assertFalse(nav.canGoBack)
 }
 @Test fun homeKeepsTheTimelineStateAndDropsEveryPage() {
  val nav=navigator("timeline","settings","appearance")
  nav.home()
  assertEquals("timeline",nav.route);assertEquals(listOf("2:appearance","1:settings"),forgotten)
 }
 @Test fun everyPageHasOneTitleAndNotesShowNone() {
  assertEquals("AI",routeTitle("ai"));assertEquals("Settings",routeTitle("settings"));assertEquals("Export",routeTitle("data"))
  assertNull(routeTitle("note:a"));assertNull(routeTitle("search"));assertNull(routeTitle("timeline"))
 }
}
