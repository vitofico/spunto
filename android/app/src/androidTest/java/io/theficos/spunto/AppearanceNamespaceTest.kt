package io.theficos.spunto

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.theficos.spunto.core.design.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class AppearanceNamespaceTest {
 @get:Rule val compose=createComposeRule()
 @Test fun protectedSurfacesUseCurrentReplicaThemeAndReactToAccountChange() {
  val context=InstrumentationRegistry.getInstrumentation().targetContext
  val first="lock-theme-${UUID.randomUUID()}";val second="lock-theme-${UUID.randomUUID()}"
  val preferences=context.getSharedPreferences("appearance-$first",Context.MODE_PRIVATE)
  val other=context.getSharedPreferences("appearance-$second",Context.MODE_PRIVATE)
  val selected=mutableStateOf(first);val visible=mutableStateOf(true);var seen:Color?=null
  try {
   preferences.edit().putString("theme","salvia").putString("mode","dark").putBoolean("black",true).commit()
   other.edit().putString("theme","corallo").putString("mode","light").commit()
   compose.setContent {if(visible.value) SpuntoTheme(namespace=selected.value) {val primary=MaterialTheme.colorScheme.primary;val background=MaterialTheme.colorScheme.background;SideEffect {seen=primary;if(selected.value==first) assertEquals(Color.Black,background)}}}
   compose.waitUntil(5000) {seen==tokenColor(builtInTokens.getValue("salvia").getValue("dark").getValue("accent"))}
   compose.runOnIdle {selected.value=second}
   compose.waitUntil(5000) {seen==tokenColor(builtInTokens.getValue("corallo").getValue("light").getValue("accent"))}
  } finally {
   compose.runOnIdle {visible.value=false};compose.waitForIdle()
   listOf(first,second).forEach {context.deleteSharedPreferences("appearance-$it");context.deleteSharedPreferences("plugin-manifest-$it")}
  }
 }
}
