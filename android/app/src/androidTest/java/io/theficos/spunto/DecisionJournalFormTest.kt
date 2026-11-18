package io.theficos.spunto

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import io.theficos.spunto.core.design.SpuntoTheme
import kotlinx.serialization.json.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class DecisionJournalFormTest {
    @get:Rule val compose = createComposeRule()

    @Test fun captureKeepsDraftAndRequestIdentityAcrossDisconnectAndRestoration() {
        val schema = Json.parseToJsonElement("""{
            "type":"object", "required":["request_id","decision","private"], "properties":{
                "request_id":{"type":"string","default":"request-identity"},
                "decision":{"type":"string","minLength":1,"maxLength":500},
                "review_on":{"type":"string","maxLength":10,"default":""},
                "private":{"type":"boolean","default":true},
                "note_id":{"type":"string","default":"private-source"}
            }
        }""").jsonObject
        val initial = buildJsonObject { put("note_id", "private-source") }
        val online = mutableStateOf(true)
        var submitted: JsonObject? = null
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            SpuntoTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    DecisionForm(schema, "record-decision", initial, online.value, false) { submitted = it }
                }
            }
        }
        fun fill(label: String, value: String) = compose.onNode(hasSetTextAction() and hasText(label))
            .performScrollTo().performTextReplacement(value)
        val decision = "Use the smallest database that meets our needs."
        fill("What did you decide?", decision)
        compose.onNodeWithText("Private", substring = false).assertDoesNotExist()
        compose.onNodeWithText("This decision will be private because the source note is private.").assertExists()
        compose.runOnIdle { online.value = false }
        compose.onNodeWithText("Save decision").assertIsNotEnabled()
        fill("Review date (optional)", "2030-02-30")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText(decision).assertExists()
        compose.runOnIdle { online.value = true }
        compose.onNodeWithText("Save decision").performScrollTo().performClick()
        compose.onNodeWithText("Enter a valid date in YYYY-MM-DD format.").assertExists()
        compose.runOnIdle { assertNull(submitted) }
        fill("Review date (optional)", "2032-02-29")
        compose.onNodeWithText("Save decision").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(decision, submitted?.get("decision")?.jsonPrimitive?.content)
            assertEquals("request-identity", submitted?.get("request_id")?.jsonPrimitive?.content)
            assertEquals("private-source", submitted?.get("note_id")?.jsonPrimitive?.content)
            assertEquals(true, submitted?.get("private")?.jsonPrimitive?.boolean)
            assertEquals("2032-02-29", submitted?.get("review_on")?.jsonPrimitive?.content)
        }
    }
}
