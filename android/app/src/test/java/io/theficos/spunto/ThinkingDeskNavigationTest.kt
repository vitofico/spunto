package io.theficos.spunto

import io.theficos.spunto.core.plugins.string
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(application = android.app.Application::class, sdk = [28])
class ThinkingDeskNavigationTest {
    private val empty = JsonObject(emptyMap())
    private val legacy = Json.parseToJsonElement("""
        {"id":"thinking-desk","version":"1.1.0","origin":"bundled","state":"enabled",
         "contributes":{"commands":[{"id":"desk","title":"Thinking desk","where":["palette","note-actions"]}]}}
    """).jsonObject

    @Test fun onlyCurrentOwnersSeeTheDedicatedEntryEvenWithALegacyManifest() {
        fun manifest(vararg plugins: JsonObject) = buildJsonObject { put("plugins", JsonArray(plugins.toList())) }
        assertTrue(thinkingDeskAvailable(manifest(legacy), owner = true))
        assertFalse(thinkingDeskAvailable(manifest(legacy), owner = false))
        assertFalse(thinkingDeskAvailable(manifest(), owner = true))
        assertFalse(thinkingDeskAvailable(manifest(legacy, legacy), owner = true))
        assertFalse(thinkingDeskAvailable(manifest(JsonObject(legacy + ("enabled" to JsonPrimitive(false)))), owner = true))
        assertFalse(thinkingDeskAvailable(manifest(JsonObject(legacy + ("origin" to JsonPrimitive("operator")))), owner = true))
        assertTrue(noteTools(settingsTestResources(), listOf(legacy)).isEmpty())
        assertTrue(globalTools(manifest(legacy), manifest(legacy)).isEmpty())
        assertTrue(pluginDetailCommands(NativePluginFacts(legacy), false).isEmpty())
    }

    @Test fun listJoinsFullTitlesAndAppendsTheNextPageInServerOrder() {
        val state = ThinkingDeskSession()
        state.receive(result(empty, "Recently worked on in a much longer title", "recent", more = true))
        assertFalse(state.insideDesk)
        assertEquals("Nuovo tavolo", state.index?.newTitle)
        assertEquals("next", state.index?.more?.second?.string("cursor"))
        state.receive(result(buildJsonObject { put("cursor", "next") }, "Older", "older"))
        assertEquals(listOf("Recently worked on in a much longer title", "Older"), state.index?.desks?.map { it.title })
        assertNull(state.index?.more)
        state.receive(commandEnvelope(ThinkingDeskRoute, "desk", buildJsonObject { put("desk_id", "older") }, empty))
        assertTrue(state.insideDesk)
        assertNull(state.index)
        state.reset()
        assertFalse(state.insideDesk)
        assertNull(state.result)
    }

    private fun result(args: JsonObject, title: String, id: String, more: Boolean = false): JsonObject {
        fun command(title: String, field: String, value: String) = buildJsonObject {
            put("type", "command"); put("id", "desk"); put("title", title)
            put("args", buildJsonObject { put(field, value) })
        }
        return commandEnvelope(ThinkingDeskRoute, "desk", args, buildJsonObject {
            put("ui", 1)
            put("items", buildJsonArray {
                add(command("Nuovo tavolo", "action", "new"))
                add(buildJsonObject { put("type", "text"); put("text", title) })
                add(command("Apri tavolo", "desk_id", id))
                if (more) add(command("Altri tavoli", "cursor", "next"))
            })
        })
    }
}
