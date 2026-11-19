package io.theficos.spunto

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.graphics.Bitmap
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.theficos.spunto.core.design.SpuntoTheme
import io.theficos.spunto.core.plugins.Vocabulary
import io.theficos.spunto.core.plugins.outputComponents
import io.theficos.spunto.data.local.ReplicaDatabaseCache
import io.theficos.spunto.data.local.viewMembership
import io.theficos.spunto.data.sync.ApiClient
import io.theficos.spunto.data.sync.AuthConfig
import io.theficos.spunto.data.sync.AuthStore
import io.theficos.spunto.data.sync.SyncScheduler
import java.io.File
import java.util.Locale
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real server workflows with isolated test credentials and a dedicated synthetic replica. */
@RunWith(AndroidJUnit4::class)
class NewFeatureWorkflowTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val timeout = org.junit.rules.Timeout(5, TimeUnit.MINUTES)

    private val empty = JsonObject(emptyMap())
    private lateinit var api: ApiClient
    private lateinit var testConfig: AuthConfig
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
        // The UI smoke test copies this credential only into its isolated cache and replica.
        testConfig = AuthConfig(address, credential.token, credential.id, credential.name, true)
        api = ApiClient(testConfig)
    }

    private fun enable(plugin: String) = runBlocking {
        val catalog = api.get("/api/v1/plugins")["plugins"]!!.jsonArray
        val declaration = catalog.map { it.jsonObject }.single { it["id"]!!.jsonPrimitive.content == plugin }
        api.post("/api/v1/plugins/$plugin/enable", buildJsonObject {
            put("permissions", declaration["permissions"]!!)
            declaration["review_token"]?.let { put("review_token", it) }
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
                                    if (plugin == DecisionJournalId && component["type"]?.jsonPrimitive?.content == "form") {
                                        DecisionForm(component["schema"]!!.jsonObject, current.command, current.args, online = true, running = false) { values ->
                                            send(current.command, JsonObject(current.args + values))
                                        }
                                    } else Vocabulary(
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

    @Test fun italianAppLocaleLocalizesARealPluginFormAndKeepsTheWireValues() {
        enable("context-packs")
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val configuration = Configuration(base.resources.configuration).apply { setLocale(Locale.ITALIAN) }
        val context = base.createConfigurationContext(configuration)
        val result = runBlocking {
            pluginApi(testConfig, context.resources).requestPayload("/api/v1/plugins/context-packs/commands/edit", "POST", empty)
        }
        assertEquals("it", result.contentLanguage)
        val form = outputComponents(result.body).single { it["type"]?.jsonPrimitive?.content == "form" }
        val fields = form["schema"]!!.jsonObject["properties"]!!.jsonObject
        assertEquals("Titolo della scheda", fields["title"]!!.jsonObject["title"]!!.jsonPrimitive.content)
        assertEquals("save", fields["action"]!!.jsonObject["enum"]!!.jsonArray[0].jsonPrimitive.content)
        var submitted: JsonObject? = null
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides configuration) {
                SpuntoTheme("carta", "light") {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                        Text(context.getString(R.string.settings_plugin_context_packs_name), style = MaterialTheme.typography.titleLarge)
                        Vocabulary(form, onForm = { submitted = it },
                            choiceLabel = { field, value -> pluginFormChoice(context.resources, "context-packs", field, value) })
                    }
                }
            }
        }
        compose.onNodeWithText("Titolo della scheda").assertIsDisplayed()
        compose.onNodeWithText("Brief title").assertDoesNotExist()
        if (InstrumentationRegistry.getArguments().getString("spunto.workflow.screenshot") == "yes") {
            val directory = requireNotNull(base.getExternalFilesDir(null))
            File(directory, "plugin-form-it.png").outputStream().use {
                check(compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        }
        compose.onNodeWithText("Esegui").performScrollTo().performClick()
        compose.onNodeWithText("Scegli un valore per «Titolo della scheda».").assertExists()
        fill("Titolo della scheda", "Synthetic native plugin form")
        compose.onNodeWithText("Salva").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Esegui").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("save", submitted?.get("action")?.jsonPrimitive?.content) }
    }

    private fun isolatedPluginReplica(app: SpuntoApplication, prefix: String, displayContext: Context = app): ReplicaContainer {
        val files = File(app.cacheDir, prefix).apply { mkdirs() }
        val context = object : ContextWrapper(displayContext) {
            private fun databaseName(name: String) = File(name).name.let { if (it.startsWith(prefix)) it else prefix + it }
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = files
            override fun getDatabasePath(name: String): File = app.getDatabasePath(databaseName(name))
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
                app.openOrCreateDatabase(databaseName(name), mode, factory)
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?): SQLiteDatabase =
                app.openOrCreateDatabase(databaseName(name), mode, factory, errorHandler)
            override fun getSharedPreferences(name: String, mode: Int) = app.getSharedPreferences(prefix + name, mode)
        }
        val auth = AuthStore(context)
        val connected = MutableStateFlow(true)
        runBlocking { auth.save(testConfig) }
        return ReplicaContainer(context, testConfig.replicaId, 1, auth, AppLock(context), connected,
            SyncScheduler(context), ReplicaDatabaseCache(context), publishesPlugins = false)
    }

    @Test fun italianReaderAcceptsMachineAndAgentNotesAndNavigatesThroughAPluginResult() {
        val arguments = InstrumentationRegistry.getArguments()
        val machineId = arguments.getString("spunto.acceptance.machine")
        val agentId = arguments.getString("spunto.acceptance.agent")
        assumeTrue("Needs isolated acceptance fixtures", machineId != null && agentId != null)
        requireNotNull(machineId)
        requireNotNull(agentId)
        val machine = note(machineId)
        val agent = note(agentId)
        require(machine["body"]!!.jsonPrimitive.content.startsWith("# Sintetica dal plugin"))
        require(agent["body"]!!.jsonPrimitive.content.startsWith("# Sintetica da un agente"))
        assertTrue(machine["machine"]!!.jsonPrimitive.boolean)
        assertTrue(machine["private"]!!.jsonPrimitive.boolean)
        assertEquals("agent", agent["origin"]!!.jsonPrimitive.content)
        runBlocking { api.post("/api/v1/plugins/agent-inbox/disable", empty) }

        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as SpuntoApplication
        val configuration = Configuration(app.resources.configuration).apply { setLocale(Locale.ITALIAN) }
        val context = app.createConfigurationContext(configuration)
        val prefix = "native-note-acceptance-$suffix-"
        val container = isolatedPluginReplica(app, prefix, context)
        lateinit var model: SpuntoViewModel
        val mounted = mutableStateOf(true)
        val screenshots = arguments.getString("spunto.workflow.screenshot") == "yes"
        fun screenshot(name: String) {
            if (!screenshots) return
            compose.waitForIdle()
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            automation.waitForIdle(600, 5_000)
            val image = requireNotNull(automation.takeScreenshot())
            val directory = requireNotNull(app.getExternalFilesDir(null))
            File(directory, name).outputStream().use {
                check(image.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        }
        fun updateManifest() {
            val state = runBlocking { api.manifest() }
            val catalog = runBlocking { api.get("/api/v1/plugins") }
            compose.runOnIdle { container.manifest.store(state); model.manifest.value = state; model.catalog.value = catalog }
        }
        compose.runOnIdle { model = SpuntoViewModel(app, container) }
        try {
            updateManifest()
            compose.setContent {
                CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides configuration) {
                    if (mounted.value) SpuntoApp(model, initialNoteId = machineId)
                }
            }
            compose.waitUntil(20_000) { model.online.value && compose.onAllNodesWithText("Accetta nota").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Usa questa nota come le altre. Resterà privata e fuori dai plugin di IA e di rete.").assertIsDisplayed()
            screenshot("android-note-accept-machine-it.png")
            compose.onNodeWithText("Accetta nota").performClick()
            compose.waitUntil(20_000) { runBlocking { model.repository.dao.get(machineId)?.machine == false } }
            compose.onNodeWithText("Accetta nota").assertDoesNotExist()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Nota accettata").fetchSemanticsNodes().isNotEmpty() }
            val adopted = note(machineId)
            assertFalse(adopted["machine"]!!.jsonPrimitive.boolean)
            for (field in listOf("body", "source", "origin", "private")) assertEquals(machine[field], adopted[field])
            assertTrue(adopted["version"]!!.jsonPrimitive.int > machine["version"]!!.jsonPrimitive.int)

            compose.waitUntil(10_000) { compose.onAllNodesWithText("Nota accettata").fetchSemanticsNodes().isEmpty() }
            enable(AgentInboxId)
            updateManifest()
            val agentTitle = agent["title"]!!.jsonPrimitive.content
            compose.runOnIdle {
                model.remote.value = commandEnvelope(AgentInboxId, "preview", empty, buildJsonObject {
                    put("ui",1)
                    put("items",buildJsonArray {
                        add(buildJsonObject {put("type","markdown");put("text","# Dagli agenti\n\nApri la nota per leggerla prima di accettarla.")})
                        add(buildJsonObject {put("type","notes");put("items",buildJsonArray {
                            add(buildJsonObject {put("id",agentId);put("reason","Scritta da un agente. Da controllare.")})
                        })})
                    })
                })
            }
            compose.waitUntil(10_000) { compose.onAllNodesWithText(agentTitle).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Chiudi").assertIsDisplayed().assertHasClickAction()
            val noteRow=compose.onNode(hasText(agentTitle) and hasClickAction())
            noteRow.assertIsDisplayed()
            val header=compose.onNodeWithText("Dagli agenti").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val footer=compose.onNodeWithText("Copia").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue("Short results keep their actions near the content",footer.bottom-header.top<context.resources.displayMetrics.heightPixels/2f)
            screenshot("android-plugin-result-it.png")
            noteRow.performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Accetta nota").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Un agente ha scritto questa nota").assertIsDisplayed()
            compose.onNodeWithText("Accetta nota").performClick()
            compose.waitUntil(20_000) { model.outputs.value.any { it.noteId==agentId && viewMembership(it)?.let { member ->
                member.pluginId==AgentInboxId && member.viewId=="reviewed"
            }==true } }
            compose.onNodeWithText("Accetta nota").assertDoesNotExist()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Nota accettata").fetchSemanticsNodes().isNotEmpty() }
            val accepted=note(agentId)
            for (field in listOf("body", "source", "origin", "version", "machine")) assertEquals(agent[field],accepted[field])
        } finally {
            compose.runOnIdle { mounted.value = false; model.retire(); container.retire() }
            runBlocking { container.auth.clear() }
            container.database.close()
            app.deleteDatabase(prefix + "spunto.db")
        }
    }

    @Test fun italianReaderComparesAgentEditBesideAcceptanceAndShowsOnlyContextualTools() {
        val arguments = InstrumentationRegistry.getArguments()
        val id = arguments.getString("spunto.acceptance.edited")
        assumeTrue("Needs a disposable agent-edit fixture", id != null)
        requireNotNull(id)
        for (plugin in listOf(AgentInboxId, DecisionJournalId, "links")) enable(plugin)
        val saved = note(id)
        val before = saved["agent_review"]!!.jsonObject["before"]!!.jsonObject["body"]!!.jsonPrimitive.content
        val after = saved["body"]!!.jsonPrimitive.content
        require("Tuesday" in before && "Thursday" in after)

        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as SpuntoApplication
        val configuration = Configuration(app.resources.configuration).apply { setLocale(Locale.ITALIAN) }
        val context = app.createConfigurationContext(configuration)
        val prefix = "native-agent-edit-$suffix-"
        val container = isolatedPluginReplica(app, prefix, context)
        val state = runBlocking { api.manifest() }
        val catalog = runBlocking { api.get("/api/v1/plugins") }
        lateinit var model: SpuntoViewModel
        val mounted = mutableStateOf(true)
        compose.runOnIdle {
            applyWindowNightMode(context,"system")
            model = SpuntoViewModel(app, container)
            container.manifest.store(state)
            model.manifest.value = state
            model.catalog.value = catalog
        }
        fun screenshot(name: String) {
            if (arguments.getString("spunto.workflow.screenshot") != "yes") return
            compose.waitForIdle()
            val image = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
            File(requireNotNull(app.getExternalFilesDir(null)), name).outputStream().use {
                check(image.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        }
        try {
            compose.setContent {
                CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides configuration) {
                    if (mounted.value) SpuntoApp(model, initialNoteId = id)
                }
            }
            compose.waitUntil(20_000) { compose.onAllNodesWithText("Prima").fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
            compose.onNodeWithText("Un agente ha modificato questa nota").assertIsDisplayed()
            compose.onNodeWithText("Dopo").assertIsDisplayed()
            compose.onNodeWithText("… Meet at the library on Tuesday at 10:00. …").assertIsDisplayed()
            compose.onNodeWithText("… Meet at the library on Thursday at 14:00. …").assertIsDisplayed()
            compose.onNodeWithText("Accetta modifiche").assertIsDisplayed().assertIsEnabled()
            screenshot("android-agent-edit-it.png")

            compose.onNodeWithContentDescription("Altre azioni per la nota").performClick()
            compose.onNodeWithText("Azioni della nota").assertIsDisplayed()
            screenshot("android-note-tools-it.png")
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Collega a una nota").fetchSemanticsNodes().isNotEmpty() }
            val link = compose.onNodeWithText("Collega a una nota")
            if (!link.isDisplayed()) link.performScrollTo()
            link.assertIsDisplayed().assertIsEnabled()
            for (label in listOf("Altro", "Registra decisione", "Leggi testo immagine", "Trascrivi registrazione", "Recupera dettagli del collegamento")) {
                compose.onNodeWithText(label).assertDoesNotExist()
            }
            val manage = compose.onNodeWithText("Fissa strumenti")
            if (!manage.isDisplayed()) manage.performScrollTo()
            manage.performClick()
            val pin = compose.onNodeWithContentDescription("Fissa Collega a una nota per accedervi rapidamente")
            if (!pin.isDisplayed()) pin.performScrollTo()
            pin.performClick()
            compose.onNodeWithContentDescription("Rimuovi Collega a una nota dagli strumenti fissati").assertExists()
            androidx.test.espresso.Espresso.pressBack()
            val shortcut = compose.onNode(hasText("Collega a una nota") and hasClickAction())
            compose.onNodeWithText("Azioni della nota").assertDoesNotExist()
            if (!shortcut.isDisplayed()) compose.onAllNodes(hasScrollAction())[0].performScrollToNode(hasText("Collega a una nota"))
            shortcut.assertIsDisplayed().assertIsEnabled()
            assertEquals(listOf("links/link"), NoteToolPins(container.preferences).ids)

            compose.runOnIdle { container.connected.value = false }
            compose.waitUntil(5_000) { !model.online.value }
            compose.onNodeWithText("Collega a una nota").assertDoesNotExist()
            val accept = compose.onNodeWithText("Accetta modifiche")
            compose.onAllNodes(hasScrollAction())[0].performScrollToNode(hasText("Accetta modifiche"))
            accept.assertIsNotEnabled()
            compose.runOnIdle { container.connected.value = true }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Prima").fetchSemanticsNodes().isNotEmpty() }
            accept.performClick()
            compose.waitUntil(20_000) { model.outputs.value.any { it.noteId == id && viewMembership(it)?.viewId == "reviewed" } }
            compose.onNodeWithText("Accetta modifiche").assertDoesNotExist()
            val accepted = note(id)
            for (field in listOf("body", "source", "origin", "version", "machine")) assertEquals(saved[field], accepted[field])
        } finally {
            compose.runOnIdle { mounted.value = false; model.retire(); container.retire() }
            runBlocking { container.auth.clear() }
            container.database.close()
            app.deleteDatabase(prefix + "spunto.db")
        }
    }

    @Test fun applicationShowsDecisionCaptureOnlyWhenUsableAndKeepsAnOpenFormOffline() {
        runBlocking { api.post("/api/v1/plugins/decision-journal/disable", empty) }
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as SpuntoApplication
        val prefix = "native-decision-$suffix-"
        val container = isolatedPluginReplica(app, prefix)
        val auth = container.auth
        val connected = container.connected
        lateinit var model: SpuntoViewModel
        val mounted = mutableStateOf(true)
        fun updateManifest() {
            val manifest = runBlocking { api.manifest() }
            compose.runOnIdle { container.manifest.store(manifest); model.manifest.value = manifest }
        }
        compose.runOnIdle { model = SpuntoViewModel(app, container) }
        try {
            updateManifest()
            compose.setContent { if (mounted.value) SpuntoApp(model) }
            compose.waitUntil(15_000) { model.online.value }
            compose.onNodeWithText("Record decision").assertDoesNotExist()

            enable(DecisionJournalId)
            updateManifest()
            compose.onNode(hasScrollAction()).performScrollToNode(hasText("Record decision") and hasClickAction())
            compose.onNodeWithText("Record decision").assertIsDisplayed().performClick()
            compose.waitUntil(15_000) { compose.onAllNodesWithText("What did you decide?").fetchSemanticsNodes().isNotEmpty() }
            val decision = "Keep the synthetic launch reversible $suffix."
            fill("What did you decide?", decision)
            compose.runOnIdle { connected.value = false }
            compose.waitUntil(5_000) { !model.online.value }
            compose.onNodeWithText("Save decision").assertIsNotEnabled()
            compose.onNodeWithText(decision).assertExists()
            compose.runOnIdle { connected.value = true }
            compose.waitUntil(10_000) { model.online.value }
            compose.onNodeWithText("Save decision").performScrollTo().performClick()
            compose.waitUntil(20_000) { compose.onAllNodesWithText("Open decision note").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Decision saved.").assertExists()
            compose.onNodeWithText("Open decision note").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Review decision").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("View outcomes").assertIsDisplayed()
            compose.runOnIdle { connected.value = false }
            compose.waitUntil(5_000) { !model.online.value }
            compose.onNodeWithText("Review decision").assertDoesNotExist()
            compose.onNodeWithText("View outcomes").assertDoesNotExist()
            assertTrue(compose.onAllNodesWithText(decision, substring = true).fetchSemanticsNodes().isNotEmpty())
        } finally {
            compose.runOnIdle { mounted.value = false; model.retire(); container.retire() }
            runBlocking { auth.clear() }
            container.database.close()
            app.deleteDatabase(prefix + "spunto.db")
        }
    }

    @Test fun decisionFormsReviewAndReopenPreserveOriginalOwnerWords() {
        enable("decision-journal")
        val flow = Workflow("decision-journal", "record-decision")
        fill("What did you decide?", "Use one local SQLite file.")
        compose.onNodeWithText("Decision title (optional)").assertDoesNotExist()
        compose.onNodeWithText("Add details").performScrollTo().performClick()
        fill("Decision title (optional)", "Native decision $suffix")
        fill("Alternatives considered (optional)", "Run a separate database service.")
        val rationale = "One owner needs predictable operations.\nRetain these original owner words."
        val expected = "Restore the synthetic library in five minutes."
        fill("Why this choice? (optional)", rationale)
        fill("Expected outcome (optional)", expected)
        fill("Review date (optional)", "2030-02-01")
        compose.onNodeWithText("Request ID", substring = false).assertDoesNotExist()
        flow.click("Save decision")
        val rootId = ids(flow.output()).single()
        val original = note(rootId)
        val originalBody = original["body"]!!.jsonPrimitive.content
        assertTrue(originalBody.contains(rationale))
        assertTrue(originalBody.contains(expected))
        assertEquals("owner", original["origin"]!!.jsonPrimitive.content)
        assertFalse(original["machine"]!!.jsonPrimitive.boolean)

        flow.open("review-decision", buildJsonObject { put("note_id", rootId) })
        fill("What happened or why reopen?", "The synthetic restore took three minutes.")
        fill("What did you learn? (optional)", "Keep a second backup copy.")
        compose.onNodeWithText("Closed", substring = false).performScrollTo().performClick()
        flow.click("Record outcome")
        val outcomeId = ids(flow.output()).first()
        val outcomeBody = note(outcomeId)["body"]!!.jsonPrimitive.content
        assertTrue(outcomeBody.contains("/n/$rootId"))
        assertTrue(outcomeBody.contains("three minutes"))
        assertEquals(originalBody, note(rootId)["body"]!!.jsonPrimitive.content)

        // The outcome note also resolves its original journal entry when reopening.
        flow.open("review-decision", buildJsonObject { put("note_id", outcomeId) })
        fill("What happened or why reopen?", "A new storage constraint requires another review.")
        fill("Next review date (optional)", "2030-03-01")
        compose.onNodeWithText("Open for follow-up", substring = false).performScrollTo().performClick()
        flow.click("Record outcome")
        assertNotEquals(outcomeId, ids(flow.output()).first())
        assertEquals(originalBody, note(rootId)["body"]!!.jsonPrimitive.content)
        assertEquals(outcomeBody, note(outcomeId)["body"]!!.jsonPrimitive.content)
        flow.open("journal", buildJsonObject { put("note_id", rootId) })
        assertTrue(resultCopyText(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.resources,flow.output()).contains("Open · Review on 2030-03-01"))
    }
}
