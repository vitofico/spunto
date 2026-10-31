package io.theficos.spunto

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.theficos.spunto.core.design.SpuntoTheme
import io.theficos.spunto.core.plugins.Vocabulary
import io.theficos.spunto.core.plugins.outputComponents
import io.theficos.spunto.data.sync.ApiClient
import io.theficos.spunto.data.sync.AuthConfig
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real server workflows in an isolated Compose activity; no app account or replica access. */
@RunWith(AndroidJUnit4::class)
class NewFeatureWorkflowTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val timeout = org.junit.rules.Timeout(5, TimeUnit.MINUTES)

    private val empty = JsonObject(emptyMap())
    private lateinit var api: ApiClient
    private val suffix = java.lang.Long.toUnsignedString(System.nanoTime(), 36)

    @Before fun requireDisposableLoopbackServer() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(
            "Needs explicit synthetic workflow authorization",
            arguments.getString("spunto.workflow.disposable") == "SPUNTO-SYNTHETIC-WORKFLOWS",
        )
        val address = requireNotNull(arguments.getString("spunto.workflow.url")) {
            "Set spunto.workflow.url to the disposable loopback server."
        }
        val uri = URI(address)
        require(
            uri.scheme == "http" && uri.host in setOf("127.0.0.1", "localhost", "::1", "[::1]") &&
                uri.port in 1..65535 && uri.rawPath in setOf("", "/") &&
                uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null,
        ) { "Workflow tests require a loopback HTTP server with an explicit port." }
        val anonymous = ApiClient(AuthConfig(address, "", "synthetic-workflow", "Synthetic workflow", true))
        val credential = anonymous.signIn(
            arguments.getString("spunto.workflow.password") ?: "browser acceptance password",
            "Synthetic workflow $suffix",
        )
        // The token exists only in this test's memory, never AuthStore or the local replica.
        api = ApiClient(AuthConfig(address, credential.token, credential.id, credential.name, true))
    }

    private fun enable(plugin: String) = runBlocking {
        val catalog = api.get("/api/v1/plugins")["plugins"]!!.jsonArray
        val declaration = catalog.map { it.jsonObject }.single { it["id"]!!.jsonPrimitive.content == plugin }
        api.post("/api/v1/plugins/$plugin/enable", buildJsonObject {
            put("permissions", declaration["permissions"]!!)
        })
    }

    private fun serverCommand(plugin: String, command: String, args: JsonObject): JsonObject = runBlocking {
        api.post("/api/v1/plugins/$plugin/commands/$command", args)
    }

    private fun note(id: String): JsonObject = runBlocking { api.get("/api/v1/notes/$id") }

    private data class Frame(val command: String, val args: JsonObject, val output: JsonObject, val generation: Int)

    private inner class Workflow(private val plugin: String, initialCommand: String, initialArgs: JsonObject = empty, compactLargeText: Boolean = false) {
        private val frame = mutableStateOf(Frame(initialCommand, initialArgs, serverCommand(plugin, initialCommand, initialArgs), 0))
        private val replies = AtomicInteger(0)
        private val failure = AtomicReference<String?>(null)

        init {
            compose.setContent {
                val scope = rememberCoroutineScope()
                val current = frame.value
                val send: (String, JsonObject) -> Unit = { command, args ->
                    scope.launch {
                        try {
                            val response = api.post("/api/v1/plugins/$plugin/commands/$command", args)
                            frame.value = Frame(command, args, response, current.generation + 1)
                            replies.incrementAndGet()
                        } catch (error: Exception) {
                            // Stable type only: no credentials, response payloads or note text in diagnostics.
                            failure.set(error.javaClass.simpleName)
                        }
                    }
                }
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, if(compactLargeText) 1.6f else density.fontScale)) {
                    SpuntoTheme("carta", "light") {
                        val viewport = if(compactLargeText) Modifier.width(320.dp).height(480.dp) else Modifier.fillMaxSize()
                        Column(viewport.verticalScroll(rememberScrollState()).padding(16.dp)) {
                            key(current.generation) {
                                outputComponents(current.output).forEach { component ->
                                    Vocabulary(
                                        component,
                                        onCommand = send,
                                        onForm = { values -> send(current.command, JsonObject(current.args + values)) },
                                        formInitial = current.args,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        fun output(): JsonObject {
            var result = empty
            compose.runOnIdle { result = frame.value.output }
            return result
        }

        fun open(command: String, args: JsonObject) {
            val result = serverCommand(plugin, command, args)
            compose.runOnIdle { frame.value = Frame(command, args, result, frame.value.generation + 1) }
        }

        private fun awaitReply(previous: Int) {
            compose.waitUntil(40_000) { replies.get() > previous || failure.get() != null }
            assertNull("The real server command must succeed (exception type only)", failure.get())
            assertEquals("Exactly one reply per UI action", previous + 1, replies.get())
            compose.waitForIdle()
        }

        fun click(label: String) {
            val previous = replies.get()
            compose.onNodeWithText(label, substring = false).performScrollTo().performClick()
            awaitReply(previous)
        }

        fun submit() = click("Run")
    }

    private fun fill(label: String, value: String) {
        compose.onNode(hasSetTextAction() and hasText(label, substring = false))
            .performScrollTo().performTextReplacement(value)
    }

    private fun commandArgs(output: JsonObject, title: String): JsonObject =
        outputComponents(output).single {
            it["type"]?.jsonPrimitive?.content == "command" && it["title"]?.jsonPrimitive?.content == title
        }["args"]!!.jsonObject

    private fun ids(output: JsonObject): List<String> = outputComponents(output).flatMap {
        if (it["type"]?.jsonPrimitive?.content == "notes") {
            (it["ids"] as? JsonArray).orEmpty().map { value -> value.jsonPrimitive.content }
        } else emptyList()
    }

    @Test fun linkChooserSearchesTitlesAndKeepsIdentifiersInternal() {
        enable("links")
        val source = runBlocking {api.post("/api/v1/notes", buildJsonObject {put("body", "Native link source $suffix")})}
        val title = "Native link destination $suffix"
        val destination = runBlocking {api.post("/api/v1/notes", buildJsonObject {put("body", title)})}
        val sourceId = source["id"]!!.jsonPrimitive.content
        val destinationId = destination["id"]!!.jsonPrimitive.content
        val flow = Workflow("links", "link", buildJsonObject {put("note_id", sourceId);put("query", suffix)})
        compose.onNodeWithText("Target note ID").assertDoesNotExist()
        compose.onNodeWithText("Source choices").assertDoesNotExist()
        compose.onNodeWithText(destinationId, substring = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("Choose Link to").performScrollTo().performClick()
        compose.onNodeWithText("Find an option").performTextInput("destination")
        compose.onNodeWithText("1. $title").performClick()
        flow.submit()
        val proposal = outputComponents(flow.output()).single {it["type"]?.jsonPrimitive?.content == "pill"}["suggestion_id"]!!.jsonPrimitive.content
        assertFalse(note(sourceId)["body"]!!.jsonPrimitive.content.contains("/n/"))
        runBlocking {api.post("/api/v1/suggestions/$proposal/accept", empty)}
        assertTrue(note(sourceId)["body"]!!.jsonPrimitive.content.contains("[$title](/n/$destinationId)"))
    }

    @Test fun thinkingHistoryChoosesSourcesAndConnectsAndRemovesTheirRelationAtLargeText() {
        enable("thinking-desk")
        val titles = listOf("Native history earlier idea $suffix", "Native history later idea $suffix")
        val sources = titles.map { body -> runBlocking {
            api.post("/api/v1/notes", buildJsonObject {put("body", body);put("private", true)})
        } }
        val flow = Workflow("thinking-desk", "desk", compactLargeText = true)
        flow.click("New thinking desk")
        compose.onNodeWithText("history", substring = false).assertExists()
        compose.onNodeWithText("collision", substring = false).assertExists()
        compose.onNodeWithText("history", substring = false).performScrollTo().performClick()
        fill("Topic or desk title", "Native history $suffix")
        flow.submit()
        for(title in titles) {
            flow.click("Review sources")
            fill("Search notes (blank browses recent)", title)
            flow.submit()
            compose.onNodeWithText("Source choices").assertDoesNotExist()
            compose.onNodeWithContentDescription("Choose Add source").performScrollTo().performClick()
            compose.onNodeWithText("Find an option").performTextInput(title)
            compose.onNodeWithText("1. $title", substring = false).performClick()
            flow.submit()
        }
        assertEquals(sources.map {it["id"]!!.jsonPrimitive.content}, ids(flow.output()))
        compose.onNodeWithText("Notes in creation order", substring = true).assertExists()
        compose.onAllNodesWithText("Open note", substring = false).assertCountEquals(2)
        val date = sources[0]["created_at"]!!.jsonPrimitive.content.take(10)
        assertTrue(compose.onAllNodesWithText(date, substring = true).fetchSemanticsNodes().isNotEmpty())
        flow.click("Connect notes")
        compose.onNodeWithContentDescription("Choose First note").performScrollTo().performClick()
        compose.onNodeWithText("Find an option").performTextInput("later")
        compose.onNodeWithText("2. ${titles[1]}", substring = false).performClick()
        compose.onNodeWithText("challenges", substring = false).performScrollTo().performClick()
        compose.onNodeWithContentDescription("Choose Second note").performScrollTo().performClick()
        compose.onNodeWithText("Find an option").performTextInput("earlier")
        compose.onNodeWithText("1. ${titles[0]}", substring = false).performClick()
        flow.submit()
        compose.onNodeWithText("Note 2 challenges note 1", substring = true).assertExists()
        flow.click("Connect notes")
        compose.onNodeWithContentDescription("Choose First note").performScrollTo().performClick()
        compose.onNodeWithText("Find an option").performTextInput("later")
        compose.onNodeWithText("2. ${titles[1]}", substring = false).performClick()
        compose.onNodeWithText("challenges", substring = false).performScrollTo().performClick()
        compose.onNodeWithContentDescription("Choose Second note").performScrollTo().performClick()
        compose.onNodeWithText("Find an option").performTextInput("earlier")
        compose.onNodeWithText("1. ${titles[0]}", substring = false).performClick()
        compose.onNodeWithText("Remove this connection").assertExists()
        compose.onNode(isToggleable()).performScrollTo().performClick()
        flow.submit()
        compose.onNodeWithText("Note 2 challenges note 1", substring = true).assertDoesNotExist()
        assertEquals(sources.map {it["id"]!!.jsonPrimitive.content}, ids(flow.output()))
        sources.forEachIndexed {index, source ->
            assertEquals(titles[index], note(source["id"]!!.jsonPrimitive.content)["body"]!!.jsonPrimitive.content)
        }
    }

    @Test fun thinkingDeskCollisionReviewsIdeaAndKeepsPrivateSnapshot() {
        enable("thinking-desk")
        val sources = listOf("Gardens $suffix #gardening", "Networks $suffix #computing").map { body ->
            runBlocking { api.post("/api/v1/notes", buildJsonObject {
                put("body", body)
                put("private", true)
            }) }
        }
        val deskId = java.util.UUID.randomUUID().toString()
        serverCommand("thinking-desk", "edit", buildJsonObject {
            put("request_id", deskId)
            put("title", "Collision $suffix")
            put("mode", "collision")
            put("note_id", sources[0]["id"]!!)
        })
        val sourceId = sources[1]["id"]!!.jsonPrimitive.content
        serverCommand("thinking-desk", "sources", buildJsonObject {
            put("desk_id", deskId)
            put("expected_revision", 1)
            put("add", "1. Networks $suffix #computing")
            put("source_choices", buildJsonArray { add("$sourceId@1") })
        })
        val flow = Workflow("thinking-desk", "edit", buildJsonObject {
            put("desk_id", deskId)
            put("expected_revision", 2)
        })
        val idea = "Prune weak network links like a garden."
        fill("Your reflection or new idea", idea)
        flow.submit()
        val copied = resultCopyText(
            InstrumentationRegistry.getInstrumentation().targetContext.resources, flow.output(),
        )
        assertTrue(copied.contains("surprising analogy"))
        assertTrue(copied.contains(idea))
        flow.click("Keep private note copy")
        val saved = note(ids(flow.output()).single())
        assertTrue(saved["private"]!!.jsonPrimitive.boolean)
        assertFalse(saved["machine"]!!.jsonPrimitive.boolean)
        assertTrue(saved["body"]!!.jsonPrimitive.content.contains(idea))
        assertEquals("Gardens $suffix #gardening", note(sources[0]["id"]!!.jsonPrimitive.content)["body"]!!.jsonPrimitive.content)
    }

    @Test fun contextPackFormsCreateEditAndKeepPrivateSnapshot() {
        enable("context-packs")
        val flow = Workflow("context-packs", "edit")
        val title = "Native context $suffix"
        val goals = "Keep a manually curated project brief.\nUse explicit revision tracking."
        fill("Brief title", title)
        fill("Project", "Synthetic native workflow")
        fill("Goals", goals)
        fill("Constraints", "All data belongs to a disposable local test server.")
        fill("Decisions", "Keep ordinary private note copies.")
        fill("Open questions", "What should be reviewed next?")
        compose.onNodeWithText("Creation request ID", substring = false).assertDoesNotExist()
        flow.submit()
        val editArgs = commandArgs(flow.output(), "Edit brief")
        val packId = editArgs["pack_id"]!!.jsonPrimitive.content
        assertEquals(1, editArgs["expected_revision"]!!.jsonPrimitive.int)
        val copied = resultCopyText(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.resources,flow.output())
        assertTrue(copied.contains("# $title"))
        assertTrue(copied.contains(goals))
        assertTrue(copied.contains("## Open questions"))

        flow.open("edit", editArgs)
        val revised = "A revised native goal.\nRetain the second line exactly."
        fill("Goals", revised)
        flow.submit()
        assertEquals(2, commandArgs(flow.output(), "Edit brief")["expected_revision"]!!.jsonPrimitive.int)
        assertEquals(packId, commandArgs(flow.output(), "Edit brief")["pack_id"]!!.jsonPrimitive.content)
        assertTrue(resultCopyText(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.resources,flow.output()).contains(revised))
        // This verifies the production copy formatter, not Android clipboard delivery.
        flow.click("Keep private note copy")
        val saved = note(ids(flow.output()).single())
        assertTrue(saved["private"]!!.jsonPrimitive.boolean)
        assertFalse(saved["machine"]!!.jsonPrimitive.boolean)
        assertEquals("owner", saved["origin"]!!.jsonPrimitive.content)
        assertTrue(saved["body"]!!.jsonPrimitive.content.contains(revised))
        assertTrue(saved["body"]!!.jsonPrimitive.content.contains("## Constraints"))
    }

    @Test fun decisionFormsReviewAndReopenPreserveOriginalOwnerWords() {
        enable("decision-journal")
        val flow = Workflow("decision-journal", "record-decision")
        fill("Decision title", "Native decision $suffix")
        fill("What did you decide?", "Use one local SQLite file.")
        fill("Alternatives considered", "Run a separate database service.")
        val rationale = "One owner needs predictable operations.\nRetain these original owner words."
        val expected = "Restore the synthetic library in five minutes."
        fill("Why this choice?", rationale)
        fill("Expected outcome", expected)
        fill("Review date (YYYY-MM-DD, optional)", "2030-02-01")
        compose.onNodeWithText("Request ID", substring = false).assertDoesNotExist()
        flow.submit()
        val rootId = ids(flow.output()).single()
        val original = note(rootId)
        val originalBody = original["body"]!!.jsonPrimitive.content
        assertTrue(originalBody.contains(rationale))
        assertTrue(originalBody.contains(expected))
        assertEquals("owner", original["origin"]!!.jsonPrimitive.content)
        assertFalse(original["machine"]!!.jsonPrimitive.boolean)

        flow.open("review-decision", buildJsonObject { put("note_id", rootId) })
        fill("What happened, or why reopen?", "The synthetic restore took three minutes.")
        fill("What did you learn? (optional)", "Keep a second backup copy.")
        compose.onNodeWithText("closed", substring = false).performScrollTo().performClick()
        flow.submit()
        val outcomeId = ids(flow.output()).first()
        val outcomeBody = note(outcomeId)["body"]!!.jsonPrimitive.content
        assertTrue(outcomeBody.contains("/n/$rootId"))
        assertTrue(outcomeBody.contains("three minutes"))
        assertEquals(originalBody, note(rootId)["body"]!!.jsonPrimitive.content)

        // The outcome note also resolves its original journal entry when reopening.
        flow.open("review-decision", buildJsonObject { put("note_id", outcomeId) })
        fill("What happened, or why reopen?", "A new storage constraint requires another review.")
        fill("Next review date (YYYY-MM-DD, optional)", "2030-03-01")
        compose.onNodeWithText("open", substring = false).performScrollTo().performClick()
        flow.submit()
        assertNotEquals(outcomeId, ids(flow.output()).first())
        assertEquals(originalBody, note(rootId)["body"]!!.jsonPrimitive.content)
        assertEquals(outcomeBody, note(outcomeId)["body"]!!.jsonPrimitive.content)
        flow.open("journal", buildJsonObject { put("note_id", rootId) })
        assertTrue(resultCopyText(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.resources,flow.output()).contains("Open · revision 3 · review 2030-03-01"))
    }
}
