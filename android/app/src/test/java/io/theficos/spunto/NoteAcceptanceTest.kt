package io.theficos.spunto

import io.theficos.spunto.core.model.Note
import io.theficos.spunto.data.local.PluginOutputEntity
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class NoteAcceptanceTest {
    private val note = Note("synthetic-note", "The exact displayed words", serverRev=7)
    private val featureManifest = Json.parseToJsonElement("""{
        "features":["note_adoption","agent_review_versioned"],"plugins":[{"id":"agent-inbox","enabled":true}]
    }""").jsonObject

    @Test fun machineAndAgentAcceptanceAreIndependentAndPermissionScoped() {
        val both = note.copy(machine=true,updatedViaAgent=true)
        val disabled = JsonObject(featureManifest + ("plugins" to JsonArray(emptyList())))
        assertEquals(listOf(NoteAcceptance.Machine,NoteAcceptance.AgentChanges), noteAcceptanceActions(both,emptyList(),featureManifest,true,true))
        assertEquals(listOf(NoteAcceptance.Machine), noteAcceptanceActions(both,emptyList(),featureManifest,true,false))
        assertEquals(listOf(NoteAcceptance.Machine), noteAcceptanceActions(both,emptyList(),disabled,true,true))
        assertTrue(noteAcceptanceActions(both,emptyList(),featureManifest,false,false).isEmpty())
        assertTrue(noteAcceptanceActions(both,emptyList(),JsonObject(emptyMap()),true,true).isEmpty())
        assertTrue(noteAcceptanceActions(both.copy(trashed=true),emptyList(),featureManifest,true,true).isEmpty())
        assertTrue(noteAcceptanceActions(both.copy(conflictOf="other"),emptyList(),featureManifest,true,true).isEmpty())
        assertTrue(noteAcceptanceActions(note,emptyList(),featureManifest,true,true).isEmpty())
        assertTrue(noteAcceptanceActions(note.copy(origin="agent",sourceKind="import"),emptyList(),featureManifest,true,true).isEmpty())
    }

    @Test fun syncedAgentReceiptHidesOnlyAgentReview() {
        val agent = note.copy(origin="agent",sourceKind="agent",machine=true)
        val reviewed = PluginOutputEntity("annotation","reviewed",note.id,
            """{"plugin":"agent-inbox","kind":"member:reviewed","payload":{}}""")
        assertEquals(listOf(NoteAcceptance.Machine,NoteAcceptance.AgentNote),noteAcceptanceActions(agent,emptyList(),featureManifest,true,true))
        assertEquals(listOf(NoteAcceptance.Machine),noteAcceptanceActions(agent,listOf(reviewed),featureManifest,true,true))
    }

    @Test fun acceptanceChecksTheDisplayedServerRevisionAndLocalEdits() {
        assertTrue(matchesAcceptanceRevision(note,note.copy()))
        assertFalse(matchesAcceptanceRevision(note,note.copy(serverRev=8)))
        assertFalse(matchesAcceptanceRevision(note,note.copy(body="A newer draft")))
        assertFalse(matchesAcceptanceRevision(note,note.copy(pending=true)))
        assertFalse(matchesAcceptanceRevision(note,note.copy(machine=true)))
        assertFalse(matchesAcceptanceRevision(note,note.copy(updatedViaAgent=true)))
    }
}
