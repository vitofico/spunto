package io.theficos.spunto

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.platform.app.InstrumentationRegistry
import io.theficos.spunto.core.design.SpuntoTheme
import io.theficos.spunto.core.plugins.Vocabulary
import io.theficos.spunto.core.plugins.outputComponents
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PluginWorkflowGoldensTest {
    @get:Rule val compose = createComposeRule()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun contextPackFormSubmitsItsTitleAndHiddenRequest() {
        val value = "Synthetic weekly brief"
        val result = submit("context-packs", "Brief title", value)
        assertEquals(value, result["title"]?.jsonPrimitive?.content)
    }

    @Test fun thinkingDeskFormSubmitsItsSelectedMode() {
        val value = "Synthetic history"
        val result = submit("thinking-desk", "Topic or desk title", value) {
            compose.onNodeWithText("collision").performScrollTo().performClick()
        }
        assertEquals(value, result["title"]?.jsonPrimitive?.content)
        assertEquals("collision", result["mode"]?.jsonPrimitive?.content)
    }

    @Test fun decisionFormSubmitsOwnerWordsAndKeepsRequestIdInternal() {
        val value = "Keep the synthetic weekly slot."
        val label = instrumentation.targetContext.getString(R.string.decision_choice)
        val result = submit("decision-journal", label, value)
        assertEquals(value, result["decision"]?.jsonPrimitive?.content)
    }

    private fun submit(
        plugin: String,
        field: String,
        value: String,
        beforeSubmit: () -> Unit = {},
    ): JsonObject {
        val raw = instrumentation.context.assets.open("plugin-workflow-forms.json")
            .bufferedReader().use { it.readText() }
        val fixtures = Json.parseToJsonElement(raw).jsonObject
        val components = outputComponents(fixtures.getValue(plugin))
        val schema = components.single { it["type"]?.jsonPrimitive?.content == "form" }
            .getValue("schema").jsonObject
        val request = schema.getValue("properties").jsonObject.getValue("request_id").jsonObject
        val requestId = request.getValue("default")
        val decision = plugin == "decision-journal"
        var submitted: JsonObject? = null
        compose.setContent {
            SpuntoTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (decision) {
                        DecisionForm(
                            schema, "record-decision", JsonObject(emptyMap()),
                            online = true, running = false,
                        ) { submitted = it }
                    } else {
                        components.forEach { Vocabulary(it, onForm = { values -> submitted = values }) }
                    }
                }
            }
        }

        compose.onNodeWithText(request.getValue("title").jsonPrimitive.content).assertDoesNotExist()
        compose.onNodeWithText(requestId.jsonPrimitive.content).assertDoesNotExist()
        compose.onNode(hasSetTextAction() and hasText(field)).performScrollTo()
            .assertIsDisplayed().performTextReplacement(value)
        beforeSubmit()
        val button = instrumentation.targetContext.getString(
            if (decision) R.string.decision_save else R.string.plugin_ui_run,
        )
        compose.onNodeWithText(button).performScrollTo().performClick()
        return compose.runOnIdle {
            checkNotNull(submitted).also { assertEquals(requestId, it["request_id"]) }
        }
    }
}
