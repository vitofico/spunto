package io.theficos.spunto

import kotlinx.serialization.json.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LegacyLinkPreviewTest {
    private val destination = "01939aad-84e0-7123-8123-456789abcdef"

    @Test fun readsOnlyTheExactPendingLinksProposal() {
        val valid = proposal()
        assertEquals(destination, legacyLinkDestination(pending(proposal(id = "other"), valid), "source", "proposal"))
        assertNull(legacyLinkDestination(pending(valid), "wrong-source", "proposal"))
        assertNull(legacyLinkDestination(pending(proposal(status = "accepted")), "source", "proposal"))
        assertNull(legacyLinkDestination(pending(proposal(plugin = "tidy")), "source", "proposal"))
        assertNull(legacyLinkDestination(pending(proposal(action = "replace_body")), "source", "proposal"))
    }

    @Test fun rejectsExternalAndCompoundLinksWhileAcceptingEscapedCaptions() {
        assertEquals(destination, legacyLinkDestination(pending(proposal(text = "[Current \\[title\\]](/n/$destination)")), "source", "proposal"))
        assertNull(legacyLinkDestination(pending(proposal(text = "[External](https://elsewhere.test/n/$destination)")), "source", "proposal"))
        assertNull(legacyLinkDestination(pending(proposal(text = "[Note](/n/$destination) and more")), "source", "proposal"))
    }

    private fun pending(vararg items: JsonObject) = buildJsonObject {
        put("suggestions", JsonArray(items.toList()))
    }

    private fun proposal(
        id: String = "proposal",
        plugin: String = "links",
        action: String = "append_text",
        status: String = "pending",
        text: String = "[Linked note](/n/$destination)",
    ) = buildJsonObject {
        put("id", id)
        put("note_id", "source")
        put("plugin", plugin)
        put("action", action)
        put("status", status)
        put("payload", buildJsonObject { put("text", text) })
    }
}
