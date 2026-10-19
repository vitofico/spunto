package io.theficos.spunto
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.theficos.spunto.core.design.SpuntoTheme
import io.theficos.spunto.core.plugins.Vocabulary
import kotlinx.serialization.json.*
import org.junit.Rule
import org.junit.Test
class PluginVocabularyTest {
 @get:org.junit.Rule val perTestTimeout=org.junit.rules.Timeout(10,java.util.concurrent.TimeUnit.MINUTES)
 @get:Rule val compose=createComposeRule()
 @Test fun sharedVocabularyGoldensRenderNativelyWithFallback() {
  val assets=InstrumentationRegistry.getInstrumentation().context.assets
  val fixtures=listOf("text","markdown","notes","pill","command","form","diff").map { name -> Json.parseToJsonElement(assets.open("vocabulary/$name.json").bufferedReader().use { it.readText() }).jsonObject }
  val future=Json.parseToJsonElement("""{"type":"future","fallback":{"type":"text","text":"Native fallback survives offline"}}""").jsonObject
  compose.setContent { SpuntoTheme { Column { fixtures.forEach { Vocabulary(it) }; Vocabulary(future,online=false) } } }
  compose.onNodeWithText("Three open tasks").assertExists()
  compose.onNodeWithText("From last month").assertExists()
  compose.onNodeWithText("#project").assertExists()
  compose.onNodeWithText("Mark reviewed").assertExists()
  compose.onNodeWithText("Native fallback survives offline").assertExists()
 }
}
