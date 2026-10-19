package io.theficos.spunto

import io.theficos.spunto.core.design.*
import org.junit.Assert.*
import org.junit.Test

class AppearanceChoiceTest {
 @Test fun oneExplicitAccentStillShowsTwoDistinctChoices() {
  val modes=builtInTokens.getValue("carta")
  val card=validateThemeCard(ThemeCard("fixture-pack","Fixture",light=modes.getValue("light"),dark=modes.getValue("dark"),accents=listOf(ThemeAccent("one-accent","One",modes.getValue("light"),modes.getValue("dark")))))
  assertEquals(listOf(AccentChoice(null,"Default"),AccentChoice("one-accent","One")),themeAccentChoices(card))
  assertTrue(themeAccentChoices(card.copy(accents=emptyList())).isEmpty())
  assertTrue(themeAccentChoices(card.copy(status="failed")).isEmpty())
  assertTrue(themeAccentChoices(null).isEmpty())
 }
}
