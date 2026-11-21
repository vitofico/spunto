package io.theficos.spunto.data.sync

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class WorkspaceModelSettingsTest {
    private val selections = """{"embed":{"profile":"local","model":"embedding"},"chat":{"profile":"remote","model":"old"}}"""
    private val emptyStatus = """{"can_manage":true,"model_selection":{"revision":"next","selections":{},"defaults":{}}}"""
    private fun status(manage: Boolean) = """{
        "can_manage":$manage,
        "profiles":[{"name":"local","embed_model":"embedding","leaves_network":{"embed":false}}],
        "model_selection":{"revision":"original","selections":$selections,"defaults":{}}}"""
    private fun api(server: MockWebServer) = ApiClient(
        AuthConfig(server.url("/").toString(), "device-test", "device", "Fixture", true),
    )

    @Test fun sendsOriginalRevisionAndOtherChoicesWhenResettingOneCapability() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(status(true)))
            server.enqueue(MockResponse().setBody(emptyStatus))
            val client = api(server)
            val original = client.workspaceModelSettings()
            assertEquals(false, original.leavesNetwork("embed", original.selections.getValue("embed")))
            assertNull(original.leavesNetwork("chat", original.selections.getValue("chat")))
            val saved = client.saveWorkspaceModelSettings(original, original.selections - "chat")
            assertEquals("next", saved.revision)
            assertEquals("/api/v1/ai", server.takeRequest().path)
            val put = server.takeRequest()
            assertEquals("PUT", put.method)
            assertEquals("/api/v1/ai/models", put.path)
            assertEquals("Bearer device-test", put.getHeader("Authorization"))
            assertEquals(
                Json.parseToJsonElement("""{"revision":"original","selections":{"embed":{"profile":"local","model":"embedding"}}}"""),
                Json.parseToJsonElement(put.body.readUtf8()),
            )
        }
    }

    @Test fun preservesPartialInventoryExternalRouteAndUnknownCapabilities() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(status(true)))
            server.enqueue(MockResponse().setBody("""{"profiles":[
                {"profile":"router","models":[
                    {"id":"alias","upstream_model":"host/model","leaves_network":true,"capabilities":["chat"],"vision_unverified":true},
                    {"id":"unclassified","leaves_network":false,"capabilities":null}],"error":null},
                {"profile":"other","models":[],"error":"ai_inventory_unavailable"}]}"""))
            val client = api(server)
            val inventory = client.workspaceModelInventory(client.workspaceModelSettings())
            val chat = inventory.options.first()
            assertEquals(WorkspaceModelChoice("router", "alias"), chat.choice)
            assertTrue(chat.leavesNetwork)
            assertEquals("host/model", chat.upstreamModel)
            assertTrue(chat.supports("chat"))
            assertTrue(chat.unverified("vision"))
            assertFalse(chat.supports("embed"))
            assertTrue(inventory.options.last().unverified("embed"))
            assertEquals(listOf("other"), inventory.failedProfiles)
        }
    }

    @Test fun equivalentRoutesPreferEffectiveChoiceAndVerifiedVision() {
        val first = WorkspaceModelOption(WorkspaceModelChoice("local", "alias"), false, setOf("chat"), true, null, 1)
        val second = first.copy(choice = WorkspaceModelChoice("remote", "alias"), capabilities = setOf("chat", "vision"), visionUnverified = false)
        val inventory = WorkspaceModelInventory(listOf(first, second), emptyList())
        assertEquals(listOf(second), inventory.optionsFor("chat", second.choice, first.choice))
        assertEquals(listOf(second), inventory.optionsFor("vision", null, first.choice))
        assertEquals(listOf(first), inventory.optionsFor("vision", first.choice, null))
        assertTrue(inventory.optionsFor("embed", first.choice, null).isEmpty())
    }

    @Test fun readOnlyStatusBlocksInventoryAndWrites() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(status(false)))
            val client = api(server)
            val readOnly = client.workspaceModelSettings()
            assertThrows(IllegalStateException::class.java) { runBlocking { client.workspaceModelInventory(readOnly) } }
            assertThrows(IllegalStateException::class.java) { runBlocking { client.saveWorkspaceModelSettings(readOnly, emptyMap()) } }
            assertEquals(1, server.requestCount)
        }
    }
}
