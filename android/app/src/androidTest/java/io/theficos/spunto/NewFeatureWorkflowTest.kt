package io.theficos.spunto

import android.app.LocaleManager
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.graphics.Bitmap
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import android.os.LocaleList
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.theficos.spunto.core.design.SpuntoTheme
import io.theficos.spunto.core.plugins.Vocabulary
import io.theficos.spunto.core.plugins.outputComponents
import io.theficos.spunto.core.plugins.string
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
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

/** Real server workflows with isolated test credentials and a dedicated synthetic replica. */
@RunWith(AndroidJUnit4::class)
class NewFeatureWorkflowTest {
    private val compose = createComposeRule()
    @get:Rule val nativeUi: RuleChain = RuleChain.outerRule(TestRule { base, description ->
        object : Statement() {
            override fun evaluate() {
                if (description.methodName != "thinkingDeskCollisionReviewsIdeaAndKeepsPrivateSnapshot" ||
                    !disposableWorkflowEnabled()
                ) {
                    base.evaluate()
                    return
                }

                // The navigation sheet and Desk page both use the real app resources.
                assumeTrue("Needs Android per-app locales", Build.VERSION.SDK_INT >= 33)
                val context = InstrumentationRegistry.getInstrumentation().targetContext
                val locales = requireNotNull(context.getSystemService(LocaleManager::class.java))
                val previous = locales.applicationLocales
                locales.applicationLocales = LocaleList.forLanguageTags("it-IT")
                try {
                    base.evaluate()
                } finally {
                    locales.applicationLocales = previous
                }
            }
        }
    }).around(compose)
    @get:Rule val timeout = org.junit.rules.Timeout(5, TimeUnit.MINUTES)

    private val empty = JsonObject(emptyMap())
    private lateinit var api: ApiClient
    private lateinit var testConfig: AuthConfig
    private val suffix = java.lang.Long.toUnsignedString(System.nanoTime(), 36)

    private fun disposableWorkflowEnabled(): Boolean =
        InstrumentationRegistry.getArguments().getString("spunto.workflow.disposable") == "SPUNTO-SYNTHETIC-WORKFLOWS"

    @Before fun requireDisposableLoopbackServer() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(
            "Needs explicit synthetic workflow authorization",
            disposableWorkflowEnabled(),
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
                                val components = outputComponents(current.output)
                                val packRows = if (plugin == "context-packs") contextPackLibraryRows(components) else emptyMap()
                                components.forEachIndexed { index, component ->
                                    if (index + 1 in packRows) return@forEachIndexed
                                    packRows[index]?.let { title ->
                                        androidx.compose.material3.TextButton(onClick = { send("packs", component["args"]!!.jsonObject) }) { Text(title) }
                                        return@forEachIndexed
                                    }
                                    if (plugin == DecisionJournalId && component["type"]?.jsonPrimitive?.content == "form") {
                                        DecisionForm(component["schema"]!!.jsonObject, current.command, current.args, online = true, running = false) { values ->
                                            send(current.command, JsonObject(current.args + values))
                                        }
                                    } else Vocabulary(
                                        component,
                                        onCommand = send,
                                        onForm = { values -> send(current.command, JsonObject(current.args + values)) },
                                        formInitial = current.args,
                                        formLabel = contextPackFormLabel(plugin, component) ?: thinkingDeskFormLabel(plugin, component),
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

        fun resultEnvelope(): JsonObject {
            var result = empty
            compose.runOnIdle {
                val current = frame.value
                result = commandEnvelope(plugin, current.command, current.args, current.output)
            }
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
        val flow = Workflow("thinking-desk", "desk", buildJsonObject { put("action", "new") }, compactLargeText = true)
        flow.click("Trace an idea")
        compose.onNodeWithText("Your idea", substring = false).assertDoesNotExist()
        fill("A name for this desk", "Native history $suffix")
        flow.click("Create desk")
        for((index, title) in titles.withIndex()) {
            flow.click(if(index == 0) "Choose notes" else "Manage notes")
            fill("Search notes (blank browses recent)", title)
            flow.submit()
            compose.onNodeWithText("Source choices").assertDoesNotExist()
            compose.onNodeWithContentDescription("Choose Add source").performScrollTo().performClick()
            compose.onNodeWithText("Find an option").performTextInput(title)
            compose.onNodeWithText("1. $title", substring = false).performClick()
            flow.submit()
        }
        compose.onNodeWithText("Notes in creation order", substring = true).assertExists()
        val summary = resultCopyText(InstrumentationRegistry.getInstrumentation().targetContext.resources, flow.output())
        assertTrue(titles.all { it in summary })
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
        flow.click("Save connection")
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
        flow.click("Save connection")
        compose.onNodeWithText("Note 2 challenges note 1", substring = true).assertDoesNotExist()
        sources.forEachIndexed {index, source ->
            assertEquals(titles[index], note(source["id"]!!.jsonPrimitive.content)["body"]!!.jsonPrimitive.content)
        }
    }

    @Test fun thinkingDeskCollisionReviewsIdeaAndKeepsPrivateSnapshot() {
        enable("thinking-desk")
        val garden = "Potatura delle piante $suffix"
        val network = "Progetto di rete $suffix"
        val sources = listOf(garden, network).map { title ->
            runBlocking { api.post("/api/v1/notes", buildJsonObject {
                put("body", "# $title")
                put("private", true)
            }) }["id"]!!.jsonPrimitive.content
        }
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as SpuntoApplication
        val prefix = "native-thinking-desk-$suffix-"
        val container = isolatedPluginReplica(app, prefix, publishesPlugins = true)
        val mounted = mutableStateOf(true)
        lateinit var model: SpuntoViewModel
        compose.runOnIdle { model = SpuntoViewModel(app, container) }
        fun show(label: String): SemanticsNodeInteraction {
            val matcher = hasText(label, substring = false) and hasClickAction() and !hasSetTextAction()
            compose.waitUntil(15_000) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
            return compose.onNode(matcher).performScrollTo()
        }
        fun openDeskFromNavigation() {
            compose.onNodeWithContentDescription("Apri il menu di navigazione").performClick()
            val deskEntry = hasText("Tavolo delle idee", substring = false) and hasClickAction() and hasAnyAncestor(isDialog())
            compose.waitUntil(15_000) { compose.onAllNodes(deskEntry).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(deskEntry).performScrollTo().performClick()
        }
        try {
            compose.setContent { if (mounted.value) SpuntoApp(model) }
            compose.waitUntil(20_000) {
                model.online.value && model.pluginReplicaEpoch.isNotBlank() &&
                    supportsNotePicker(model.manifest.value, "thinking-desk") &&
                    compose.onAllNodesWithContentDescription("Apri il menu di navigazione").fetchSemanticsNodes().isNotEmpty()
            }
            openDeskFromNavigation()
            show("Ricostruisci un’idea").assertHasClickAction()
            compose.onNodeWithText("La nota che hai aperto sarà la prima del tavolo.").assertDoesNotExist()
            show("Combina le note").performClick()
            compose.waitUntil(15_000) { compose.onAllNodesWithText("Nome del tavolo").fetchSemanticsNodes().isNotEmpty() }
            val title = "Ridurre il progetto $suffix"
            fill("Nome del tavolo", title)
            compose.onNodeWithText("ID della richiesta di creazione").assertDoesNotExist()
            show("Crea tavolo").performClick()
            show("Scegli le note").performClick()
            compose.waitUntil(15_000) { compose.onAllNodesWithText("Note recenti").fetchSemanticsNodes().isNotEmpty() }
            for ((index, sourceTitle) in listOf(garden, network).withIndex()) {
                fill("Cerca note", sourceTitle)
                val matchingSource = hasText(sourceTitle, substring = false) and
                    SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)
                compose.waitUntil(15_000) { compose.onAllNodes(matchingSource).fetchSemanticsNodes().isNotEmpty() }
                compose.onNode(matchingSource).performScrollTo().performClick().assertIsSelected()
                show("Aggiungi fonte").assertIsEnabled().performClick()
                compose.waitUntil(15_000) {
                    compose.onAllNodesWithText("Nota aggiunta. Selezionate: ${index + 1}.").fetchSemanticsNodes().isNotEmpty()
                }
            }
            show(garden).assertExists()
            show(network).assertExists()
            show("Torna al tavolo").performClick()
            show("Scrivi la tua idea").performClick()
            compose.waitUntil(15_000) { compose.onAllNodesWithText("La tua idea").fetchSemanticsNodes().isNotEmpty() }
            val idea = "Applica la potatura ai collegamenti meno utili."
            fill("La tua idea", idea)
            show("Salva idea").performClick()
            show("Salva come nota privata").assertExists()
            compose.onNodeWithContentDescription("Indietro").performClick()
            show(title).assertExists()
            compose.onNodeWithText("Riprendi un tavolo", substring = false).assertIsDisplayed()
            show("Nuovo tavolo").assertHasClickAction()
            if (InstrumentationRegistry.getArguments().getString("spunto.workflow.screenshot") == "yes") {
                compose.waitForIdle()
                val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
                automation.waitForIdle(600, 5_000)
                val image = requireNotNull(automation.takeScreenshot())
                File(requireNotNull(app.getExternalFilesDir(null)), "thinking-desk-resume-it.png").outputStream().use {
                    check(image.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            }
            compose.onNodeWithContentDescription("Indietro").performClick()
            openDeskFromNavigation()
            show(title).performClick()
            compose.waitUntil(15_000) { compose.onAllNodesWithText(idea, substring = true).fetchSemanticsNodes().isNotEmpty() }
            show("Salva come nota privata").performClick()
            compose.waitUntil(15_000) {
                compose.onAllNodesWithText("Nota privata salvata. Aprila qui sotto oppure torna al tavolo per continuare.").fetchSemanticsNodes().isNotEmpty()
            }
            show(title).assertExists()
            val savedId = runBlocking {
                api.request("/api/v1/search", query = mapOf("q" to suffix, "mode" to "keyword"))
                    .jsonObject.getValue("hits").jsonArray.map { it.jsonObject }
                    .single { it["title"]?.jsonPrimitive?.content == title }
                    .getValue("id").jsonPrimitive.content
            }
            val saved = note(savedId)
            assertTrue(saved["private"]!!.jsonPrimitive.boolean)
            assertFalse(saved["machine"]!!.jsonPrimitive.boolean)
            val body = saved["body"]!!.jsonPrimitive.content
            assertTrue(idea in body && sources.all { "/n/$it" in body })
            assertEquals("# $garden", note(sources.first())["body"]!!.jsonPrimitive.content)
            show(title).performClick()
            compose.waitUntil(15_000) { compose.onAllNodesWithText(idea, substring = true).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Indietro").performClick()
            show("Torna al tavolo").performClick()
            compose.waitUntil(15_000) { compose.onAllNodesWithText(idea, substring = true).fetchSemanticsNodes().isNotEmpty() }
        } finally {
            compose.runOnIdle { mounted.value = false; model.retire(); container.retire() }
            runBlocking { container.auth.clear() }
            container.database.close()
            app.deleteDatabase(prefix + "spunto.db")
        }
    }

    @Test fun collectionsOpenFromNavigationAndSaveManualSelection() {
        enable("context-packs")
        val sourceTitle = "Synthetic collection source $suffix"
        val source = runBlocking {
            api.post("/api/v1/notes", buildJsonObject { put("body", "$sourceTitle\nPrepared for the native workflow.") })
        }
        val secondTitle = "Second synthetic collection source $suffix"
        val second = runBlocking {
            api.post("/api/v1/notes", buildJsonObject { put("body", "$secondTitle\nAnother note for the native workflow.") })
        }
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as SpuntoApplication
        val prefix = "native-collections-$suffix-"
        val container = isolatedPluginReplica(app, prefix, publishesPlugins = true)
        val mounted = mutableStateOf(true)
        lateinit var model: SpuntoViewModel
        compose.runOnIdle { model = SpuntoViewModel(app, container) }
        val collectionName = "Manual collection $suffix"
        val navigation = hasContentDescription("Open navigation") and hasClickAction()
        val navigationEntry = hasText("Collections") and hasClickAction() and hasAnyAncestor(isDialog())
        try {
            compose.setContent { if (mounted.value) SpuntoApp(model) }
            compose.waitUntil(30_000) {
                model.online.value && collectionsAvailable(model.manifest.value) &&
                    compose.onAllNodes(navigation).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNode(navigation).performClick()
            compose.onNode(navigationEntry).performScrollTo().performClick()
            compose.onNodeWithText("New collection", substring = false).performScrollTo().performClick()
            val goal = hasText("What are you working on?") and hasSetTextAction()
            compose.waitUntil(15_000) { compose.onAllNodes(goal).fetchSemanticsNodes().isNotEmpty() }
            fill("What are you working on?", "Bring the synthetic evidence together.")
            fill("Collection name", collectionName)
            fill("Introduction (optional)", "Short owner-authored beginning.")
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            compose.onNodeWithText("Choose source notes", substring = false).performScrollTo().performClick()
            val picker = hasAnyAncestor(isDialog())
            val firstChoice = hasText(sourceTitle, substring = false) and hasClickAction() and picker
            val secondChoice = hasText(secondTitle, substring = false) and hasClickAction() and picker
            compose.waitUntil(20_000) { compose.onAllNodes(firstChoice).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(firstChoice).performScrollTo().performClick().assertIsOn()
            compose.onNode(secondChoice).performScrollTo().performClick().assertIsOn()
            compose.onNode(hasText("Add to selection", substring = false) and hasClickAction() and picker)
                .assertIsEnabled().performClick()
            val pin = hasText("Pin source", substring = false) and hasClickAction()
            compose.waitUntil(10_000) { compose.onAllNodes(pin).fetchSemanticsNodes().size == 2 }
            compose.onAllNodes(pin)[0].performScrollTo().performClick()
            compose.onNodeWithText("Save collection", substring = false).performScrollTo().performClick()
            val saved = hasText(collectionName, substring = false) and !hasSetTextAction()
            compose.waitUntil(20_000) { compose.onAllNodes(saved).fetchSemanticsNodes().isNotEmpty() }
            val collection = runBlocking {
                val result = api.post("/api/v1/collections", buildJsonObject { put("query", collectionName) })
                check(result["collections"]!!.jsonArray.size == 1)
                val id = result["collections"]!!.jsonArray.single().jsonObject["id"]!!
                api.post("/api/v1/collections", buildJsonObject { put("action", "read"); put("id", id) })
            }
            val links = collection["sources"]!!.jsonArray.map { it.jsonObject }
            assertEquals(listOf(source["id"], second["id"]), links.map { it["id"] })
            assertEquals(listOf(true, false), links.map { it["pinned"]!!.jsonPrimitive.boolean })
            if (InstrumentationRegistry.getArguments().getString("spunto.workflow.screenshot") == "yes") {
                compose.waitForIdle()
                val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
                automation.waitForIdle(600, 5_000)
                val directory = requireNotNull(app.getExternalFilesDir(null))
                File(directory, "android-collections-manual.png").outputStream().use {
                    check(requireNotNull(automation.takeScreenshot()).compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            }
        } finally {
            compose.runOnIdle { mounted.value = false; model.retire(); container.retire() }
            runBlocking { container.auth.clear() }
            container.database.close()
            app.deleteDatabase(prefix + "spunto.db")
        }
    }

    @Test fun contextPackFormsCreateEditAndKeepPrivateSnapshot() {
        enable("context-packs")
        val flow = Workflow("context-packs", "packs")
        val title = "Native context $suffix"
        val goals = "Keep a manually curated project brief.\nUse explicit revision tracking."
        flow.click("New context pack")
        fill("Brief title", title)
        fill("Goals", goals)
        compose.onNodeWithText("Creation request ID", substring = false).assertDoesNotExist()
        flow.click("Create draft")
        val sourceArgs = commandArgs(flow.output(), "Choose sources")
        val packId = sourceArgs["pack_id"]!!.jsonPrimitive.content
        assertEquals(1, sourceArgs["expected_revision"]!!.jsonPrimitive.int)

        flow.click("All context packs")
        flow.click("Draft · $title")
        flow.click("Choose sources")
        flow.click("Write brief")
        fill("Project", "Synthetic native workflow")
        fill("Constraints", "All data belongs to a disposable local test server.")
        fill("Decisions", "Keep ordinary private note copies.")
        fill("Open questions", "What should be reviewed next?")
        flow.click("Save brief")
        val editArgs = commandArgs(flow.output(), "Edit brief")
        assertEquals(2, editArgs["expected_revision"]!!.jsonPrimitive.int)

        flow.click("Edit brief")
        val revised = "A revised native goal.\nRetain the second line exactly."
        fill("Goals", revised)
        flow.click("Save brief")
        assertEquals(3, commandArgs(flow.output(), "Edit brief")["expected_revision"]!!.jsonPrimitive.int)
        assertEquals(packId, commandArgs(flow.output(), "Edit brief")["pack_id"]!!.jsonPrimitive.content)
        val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
        assertTrue(resultCopyText(resources, flow.resultEnvelope()).isEmpty())
        flow.click("Use brief")
        // This verifies the production copy formatter, not Android clipboard delivery.
        val copied = resultCopyText(resources, flow.resultEnvelope())
        assertTrue(copied.contains("# $title"))
        assertTrue(copied.contains(revised))
        assertTrue(copied.contains("## Open questions"))
        assertFalse(copied.contains("Agents need permission"))
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

    private fun isolatedPluginReplica(
        app: SpuntoApplication,
        prefix: String,
        displayContext: Context = app,
        publishesPlugins: Boolean = false,
    ): ReplicaContainer {
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
            SyncScheduler(context), ReplicaDatabaseCache(context), publishesPlugins = publishesPlugins)
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
        for (plugin in listOf(AgentInboxId, DecisionJournalId, "links", "link-enrichment")) enable(plugin)
        val saved = note(id)
        val before = saved["agent_review"]!!.jsonObject["before"]!!.jsonObject["body"]!!.jsonPrimitive.content
        val after = saved["body"]!!.jsonPrimitive.content
        require("Tuesday" in before && "Thursday" in after)
        val linked = runBlocking { api.post("/api/v1/notes", buildJsonObject {
            put("body", "# Synthetic event $suffix\n\nEvent details at https://example.org/agenda.")
            put("source", buildJsonObject { put("kind", "web"); put("url", "https://example.org/agenda") })
        }) }["id"]!!.jsonPrimitive.content
        assertTrue(note(linked)["note_tool_inputs"]!!.jsonObject["link"]!!.jsonPrimitive.boolean)

        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as SpuntoApplication
        val configuration = Configuration(app.resources.configuration).apply { setLocale(Locale.ITALIAN) }
        val context = app.createConfigurationContext(configuration)
        val prefix = "native-agent-edit-$suffix-"
        val container = isolatedPluginReplica(app, prefix, context)
        val state = runBlocking { api.manifest() }
        val catalog = runBlocking { api.get("/api/v1/plugins") }
        lateinit var model: SpuntoViewModel
        val mounted = mutableStateOf(true)
        val openedNote = mutableStateOf(id)
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
                    if (mounted.value) key(openedNote.value) { SpuntoApp(model, initialNoteId = openedNote.value) }
                }
            }
            compose.waitUntil(20_000) { compose.onAllNodesWithText("Prima").fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
            compose.onNodeWithText("Un agente ha modificato questa nota").assertIsDisplayed()
            compose.onNodeWithText("Dopo").assertIsDisplayed()
            compose.onNodeWithText("… Meet at the library on Tuesday at 10:00. …").assertIsDisplayed()
            compose.onNodeWithText("… Meet at the library on Thursday at 14:00. …").assertIsDisplayed()
            compose.onNodeWithText("Accetta modifiche").assertIsDisplayed().assertIsEnabled()
            screenshot("android-agent-edit-it.png")

            val linkLabel = "Collega a una nota"
            val shortcut = compose.onNode(hasText(linkLabel) and hasClickAction())
            if (!shortcut.isDisplayed()) compose.onAllNodes(hasScrollAction())[0].performScrollToNode(hasText(linkLabel))
            shortcut.assertIsDisplayed().assertIsEnabled()
            compose.onNodeWithContentDescription("Altre azioni per la nota").performClick()
            compose.onNodeWithText("Azioni della nota").assertIsDisplayed()
            screenshot("android-note-tools-it.png")
            val link = compose.onNodeWithText(linkLabel)
            if (!link.isDisplayed()) link.performScrollTo()
            link.assertIsDisplayed().assertIsEnabled()
            val fetchLabel = "Recupera dettagli del collegamento"
            for (label in listOf("Altro", "Fissa strumenti", "Registra decisione", "Leggi testo immagine", "Trascrivi registrazione", fetchLabel)) {
                compose.onNodeWithText(label).assertDoesNotExist()
            }
            androidx.test.espresso.Espresso.pressBack()
            compose.onNodeWithText("Azioni della nota").assertDoesNotExist()

            compose.runOnIdle { container.connected.value = false }
            compose.waitUntil(5_000) { !model.online.value }
            shortcut.assertDoesNotExist()
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

            compose.runOnIdle { openedNote.value = linked }
            val fetch = hasText(fetchLabel) and hasClickAction()
            compose.waitUntil(10_000) { compose.onAllNodes(fetch).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(fetch).assertIsDisplayed().assertIsEnabled()
            shortcut.assertDoesNotExist()
            screenshot("android-note-shortcuts-it.png")
            compose.runOnIdle { container.connected.value = false }
            compose.waitUntil(5_000) { !model.online.value }
            compose.onNode(fetch).assertDoesNotExist()
        } finally {
            compose.runOnIdle { mounted.value = false; model.retire(); container.retire() }
            runBlocking { container.auth.clear() }
            container.database.close()
            app.deleteDatabase(prefix + "spunto.db")
        }
    }

    @Test fun libraryStatsOpenFromActiveNavigationAndReturnToArchive() {
        enable("library-stats")
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as SpuntoApplication
        val prefix = "native-library-stats-$suffix-"
        val container = isolatedPluginReplica(app, prefix, publishesPlugins = true)
        val mounted = mutableStateOf(true)
        lateinit var model: SpuntoViewModel
        compose.runOnIdle { model = SpuntoViewModel(app, container) }
        val navigation = hasContentDescription("Open navigation") and hasClickAction()
        val stats = hasText("Library stats") and hasClickAction() and hasAnyAncestor(isDialog())
        try {
            compose.setContent { if (mounted.value) SpuntoApp(model) }
            compose.waitUntil(20_000) {
                model.online.value && libraryCommandAvailable(model.manifest.value, "library-stats", "summary") &&
                    compose.onAllNodes(navigation).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNode(navigation).performClick()
            compose.onNode(hasText("Archive") and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
            compose.onNode(navigation).assertTextContains("Archive", substring = true).performClick()
            compose.onNode(stats).performScrollTo().performClick()
            val summary = hasText("Saved notes: ", substring = true)
            compose.waitUntil(20_000) { compose.onAllNodes(summary).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(summary).assertIsDisplayed()
            compose.onNode(hasText("Close") and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
            compose.onNode(summary).assertDoesNotExist()
            compose.onNode(navigation).assertTextContains("Archive", substring = true)
        } finally {
            compose.runOnIdle { mounted.value = false; model.retire(); container.retire() }
            runBlocking { container.auth.clear() }
            container.database.close()
            app.deleteDatabase(prefix + "spunto.db")
        }
    }

    @Test fun savedWorkspaceChatModelReopensInSettingsAndNewAskAndCanBeReset() {
        val alternative = InstrumentationRegistry.getArguments().getString("spunto.workflow.chatAlternative")
        assumeTrue("Needs an alternative model on a disposable provider", !alternative.isNullOrBlank())
        requireNotNull(alternative)
        val original = runBlocking { api.get("/api/v1/ai/ask-models").getValue("default_model").jsonPrimitive.content }
        require(original != alternative)
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as SpuntoApplication
        val prefix = "native-model-settings-$suffix-"
        val container = isolatedPluginReplica(app, prefix, publishesPlugins = true)
        val mounted = mutableStateOf(true)
        lateinit var model: SpuntoViewModel
        compose.runOnIdle { model = SpuntoViewModel(app, container) }

        fun openSettings() {
            compose.onNodeWithContentDescription("Settings").performClick()
            compose.onNodeWithText("AI models").performClick()
            compose.waitUntil(15_000) {
                compose.onAllNodes(hasText("Chat") and isEnabled() and hasClickAction()).fetchSemanticsNodes().size == 1
            }
        }
        fun back() = compose.onNodeWithContentDescription("Back").performClick()
        fun save() {
            compose.onNodeWithText("Save models").performScrollTo().performClick()
            compose.waitUntil(20_000) {
                compose.onAllNodesWithText("Model choices saved.").fetchSemanticsNodes().isNotEmpty()
            }
        }
        fun verifyFreshAsk(expected: String) {
            back()
            back()
            compose.onNodeWithContentDescription("Ask your notes").performClick()
            compose.waitUntil(15_000) {
                compose.onAllNodesWithText(expected).fetchSemanticsNodes().size == 1
            }
            compose.onNodeWithText(expected).assertIsDisplayed()
            back()
        }
        try {
            compose.setContent { if (mounted.value) SpuntoApp(model) }
            compose.waitUntil(30_000) {
                supportsConversationalAsk(model.manifest.value) && model.online.value
            }
            openSettings()
            val chat = hasText("Chat") and hasClickAction()
            compose.onNode(chat and hasText("Server default (", substring = true)).performClick()
            compose.onNodeWithText(alternative, substring = true).performScrollTo().performClick()
            save()
            back()
            back()
            openSettings()
            compose.onNode(chat and hasText(alternative, substring = true)).assertIsDisplayed()
            verifyFreshAsk(alternative)

            openSettings()
            compose.onNode(chat).performClick()
            val defaults = hasText("Server default (", substring = true) and
                SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)
            compose.onNode(defaults).performClick()
            save()
            verifyFreshAsk(original)
        } finally {
            compose.runOnIdle { mounted.value = false; model.retire(); container.retire() }
            runBlocking { container.auth.clear() }
            container.database.close()
            app.deleteDatabase(prefix + "spunto.db")
        }
    }

    @Test fun askOpensFromTheHeaderWithAVisibleComposerAndStrictMode() {
        assumeTrue(
            "Requires a disposable real-model Ask provider",
            InstrumentationRegistry.getArguments().getString("spunto.workflow.ask") == "true",
        )
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as SpuntoApplication
        val prefix = "native-ask-conversation-$suffix-"
        val container = isolatedPluginReplica(app, prefix, publishesPlugins = true)
        val mounted = mutableStateOf(true)
        lateinit var model: SpuntoViewModel
        compose.runOnIdle { model = SpuntoViewModel(app, container) }
        try {
            compose.setContent { if (mounted.value) SpuntoApp(model) }
            val launcher = hasContentDescription("Ask your notes") and hasClickAction()
            compose.waitUntil(30_000) {
                supportsConversationalAsk(model.manifest.value) &&
                    compose.onAllNodes(launcher).fetchSemanticsNodes().size == 1
            }
            compose.onNode(launcher).performClick()
            val title = hasText("Ask your notes")
            val composer = hasContentDescription("Message for your notes") and isEnabled()
            compose.waitUntil(30_000) {
                model.online.value &&
                    compose.onAllNodes(title).fetchSemanticsNodes().isNotEmpty() &&
                    compose.onAllNodes(composer).fetchSemanticsNodes().size == 1
            }
            compose.onNode(title).assertIsDisplayed()
            if (InstrumentationRegistry.getArguments().getString("spunto.workflow.screenshot") == "yes") {
                compose.waitForIdle()
                val screenshot = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
                File(requireNotNull(app.getExternalFilesDir(null)), "ask-empty.png").outputStream().use {
                    check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            }
            compose.onNodeWithText("Create a note").performScrollTo().performClick()
            compose.onNode(composer).assertIsFocused().assertTextContains("Weekend shopping", substring = true)
            compose.onNode(composer).performTextClearance()
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            compose.onNodeWithText("Only from notes").assertIsDisplayed().performClick().assertIsSelected()
            compose.onNode(composer).performTextInput("What did I promise about a nonexistent synthetic code?")
            compose.onNodeWithText("Send", substring = false).assertIsDisplayed().assertIsEnabled()
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            compose.onNodeWithContentDescription("Back").performClick()
            compose.onNode(launcher).assertIsDisplayed()
            compose.onNodeWithText("Only from notes").assertDoesNotExist()
        } finally {
            compose.runOnIdle { mounted.value = false; model.retire(); container.retire() }
            runBlocking { container.auth.clear() }
            container.database.close()
            app.deleteDatabase(prefix + "spunto.db")
        }
    }

    @Test fun applicationSavesAnOfflineComposerDecisionAndReviewsTheSyncedNote() {
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
            compose.onNodeWithText("Decision").assertDoesNotExist()

            enable(DecisionJournalId)
            updateManifest()
            val decision = "Keep the synthetic launch reversible $suffix."
            val body = compose.onNodeWithContentDescription("Note text")
            body.performTextInput(decision)
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            compose.onNodeWithText("Decision").performScrollTo().performClick()
            compose.runOnIdle { connected.value = false }
            compose.waitUntil(5_000) { !model.online.value }
            fill("Why this choice? (optional)", "We can undo it.")
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            body.assertTextContains(decision)
            compose.onNodeWithText("Save").performScrollTo().performClick()
            compose.waitUntil(10_000) { model.notes.value.any { it.body == decision } }
            val saved = model.notes.value.single { it.body == decision }
            compose.runOnIdle { connected.value = true }
            runBlocking { container.sync.sync() }
            assertEquals(decision, note(saved.id).string("body"))
            compose.onNode(hasScrollAction()).performScrollToNode(hasContentDescription(decision, substring = true))
            compose.onNode(hasContentDescription(decision, substring = true) and hasClickAction()).performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Review decision").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("We can undo it.").assertExists()
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

    @Test fun decisionsManagerKeepsTypedChoiceUntilExplicitSaveAndHidesTrashedDecision() {
        enable(DecisionJournalId)
        runBlocking { api.post("/api/v1/plugins/note-assist/disable", empty) }
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as SpuntoApplication
        val prefix = "native-decisions-manager-$suffix-"
        val container = isolatedPluginReplica(app, prefix, publishesPlugins = true)
        val mounted = mutableStateOf(true)
        lateinit var model: SpuntoViewModel
        compose.runOnIdle { model = SpuntoViewModel(app, container) }
        val navigation = hasContentDescription("Open navigation") and hasClickAction()
        val choice = "Keep a reversible launch $suffix."
        fun waitFor(stage: String, selector: SemanticsMatcher, timeout: Long = 15_000, ready: () -> Boolean = { true }) {
            try {
                compose.waitUntil(timeout) { ready() && compose.onAllNodes(selector).fetchSemanticsNodes().isNotEmpty() }
            } catch (error: androidx.compose.ui.test.ComposeTimeoutException) {
                val tree = runCatching { compose.onRoot(useUnmergedTree = true).printToString(maxDepth = 12) }
                    .getOrElse { "Could not capture the semantic tree: ${it.message}" }
                throw AssertionError("Decision workflow stalled at $stage; online=${model.online.value}.\n$tree", error)
            }
        }
        try {
            compose.setContent { if (mounted.value) SpuntoApp(model) }
            waitFor("open navigation", navigation, 20_000) {
                model.online.value && model.container.manifest.enabled(DecisionJournalId)
            }
            compose.onNode(navigation).performClick()
            compose.onNode(hasText("Decisions") and hasClickAction() and hasAnyAncestor(isDialog())).performScrollTo().performClick()
            waitFor("decision manager entry", hasText("What did you decide?"), 15_000)
            fill("What did you decide?", choice)
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            compose.onNodeWithText("Continue").performScrollTo().performClick()
            waitFor("editable decision preview", hasText("Save decision"), 15_000)
            compose.onNode(hasSetTextAction() and hasText(choice)).assertExists()
            val prior = runBlocking {
                api.post("/api/v1/plugins/decision-journal/commands/journal", buildJsonObject {
                    put("manager", true); put("query", choice)
                })
            }
            assertTrue(outputComponents(prior).first().string("type") == "text")
            compose.onNodeWithText("Save decision").performScrollTo().performClick()
            waitFor("explicit save receipt", hasText("Decision saved."), 20_000)
            val decisionId = runBlocking {
                val journal = api.post("/api/v1/plugins/decision-journal/commands/journal", buildJsonObject {
                    put("manager", true); put("query", choice)
                })
                outputComponents(journal).first()
                    .getValue("items").jsonArray.single().jsonObject.getValue("id").jsonPrimitive.content
            }
            val row = hasAnyAncestor(hasTestTag("decision-list-row:$decisionId")) and hasText(choice) and hasClickAction()
            waitFor("saved decision list row", row, 20_000)
            compose.onNode(row).performScrollTo().performClick()
            waitFor("decision manager detail route", hasTestTag("decision-detail:$decisionId"))
            val trash = hasText("Move decision to trash") and hasClickAction()
            waitFor("trash action in decision detail", trash)
            compose.onNode(trash).performScrollTo().performClick()
            compose.onNode(trash and hasAnyAncestor(isDialog())).performClick()
            waitFor("returned to decision list", hasTestTag("decision-manager"))
            compose.onNode(row).assertDoesNotExist()
            runBlocking { model.synchronize().join() }
            assertTrue("The server still showed an active decision", note(decisionId).getValue("trashed").jsonPrimitive.boolean)
        } finally {
            compose.runOnIdle { mounted.value = false; model.retire(); container.retire() }
            runBlocking { container.auth.clear() }
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
