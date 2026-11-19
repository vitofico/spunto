package io.theficos.spunto

import io.theficos.spunto.core.model.Note
import io.theficos.spunto.data.local.AttachmentEntity
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

    @Test fun toolDetailMatchesOnlyTheSavedDisplayedRevisionAndUsesExplicitAgentBases() {
        val detail = Json.parseToJsonElement("""{
            "id":"synthetic-note","version":7,"body":"The exact displayed words","private":false,"machine":false,
            "agent_review":{"kind":"edited","before":{"version":4,"body":""}},
            "note_tool_inputs":{"image":false,"audio":true,"link":false,"labels":true}
        }""").jsonObject
        assertTrue(matchesNoteToolDetail(note, detail))
        assertFalse(matchesNoteToolDetail(note.copy(privateNote=true), detail))
        assertFalse(matchesNoteToolDetail(note.copy(pending=true), detail))
        assertFalse(matchesNoteToolDetail(note.copy(serverRev=8), detail))
        assertFalse(matchesNoteToolDetail(note.copy(body="different"), detail))
        assertEquals(AgentReviewDetail.Edited(""), parseAgentReview(detail))
        assertEquals(AgentReviewDetail.Edited(null), parseAgentReview(JsonObject(detail + ("version" to JsonPrimitive(4)))))
        assertEquals(AgentReviewDetail.Created, parseAgentReview(Json.parseToJsonElement("""{"agent_review":{"kind":"created","before":null}}""").jsonObject))
        assertNull(parseAgentReview(JsonObject(detail - "agent_review")))
        assertEquals(NoteToolInputs(audio=true,labels=true), parseNoteToolInputs(detail))
    }

    @Test fun olderDetailsUseProvableTypedAttachmentsAndSafeLocalTextOrLinks() {
        val scope = NoteToolScope("https://example.org", "current")
        val id = "01234567-1234-1234-1234-123456789012"
        val large = note.copy(body = "word ".repeat(300) + "![Image](/a/$id)", sourceUrl = "https://example.org/page")
        val attachment = AttachmentEntity(noteId=large.id, localPath="synthetic.png", mime="image/png", remoteId=id,
            status="uploaded", serverUrl=scope.server, serverEpoch=scope.epoch)
        assertEquals(NoteToolInputs(image=true,link=true,titleSummary=true,labels=true,shortText=true),
            legacyNoteToolInputs(large, scope, listOf(attachment)))
        val stale = attachment.copy(serverEpoch="retired")
        assertFalse(legacyNoteToolInputs(large, scope, listOf(stale)).image)
        assertFalse(legacyNoteToolInputs(large, scope, listOf(attachment.copy(mime="image/svg+xml"))).image)
        assertFalse(legacyNoteToolInputs(large.copy(sourceUrl="https://username@example.org/page"), scope, emptyList()).link)
        assertEquals(NoteToolInputs(), legacyNoteToolInputs(large.copy(privateNote=true), scope, listOf(attachment)))
        assertEquals(NoteToolInputs(), parseNoteToolInputs(null))
    }
}
