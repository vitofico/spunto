package io.theficos.spunto.core.model

import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

@Serializable
data class Note(val id: String, val body: String, val title: String = titleOf(body), val createdAt: Long = System.currentTimeMillis(), val updatedAt: Long = createdAt, val serverRev: Int = 0, val pinned: Boolean = false, val archived: Boolean = false, val trashed: Boolean = false, val privateNote: Boolean = false, val machine: Boolean = false, val conflictOf: String? = null, val sourceUrl: String? = null, val sourceKind: String = "android", val sourceRef: String? = null, val pending: Boolean = false, val syncError: String? = null, val pinnedAt: Long = 0, val archivedAt: Long = 0, val trashedAt: Long = 0, val privateAt: Long = 0, val origin: String = "owner", val updatedViaAgent: Boolean = false) {
 val bodyHash: String get() = MessageDigest.getInstance("SHA-256").digest(body.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
data class Label(val key: String, val path: String, val start: Int, val end: Int)
data class LabelCount(val key: String, val path: String, val count: Int)
@Serializable
data class OutboxOperation(val opId: String = uuidV7(), val type: String, val noteId: String? = null, val body: String? = null, val baseVersion: Int? = null, val createdAt: Long? = null, val at: Long? = null, val pinned: Boolean? = null, val archived: Boolean? = null, val trashed: Boolean? = null, val privateNote: Boolean? = null, val suggestionId: String? = null, val decision: String? = null, val sourceKind: String? = null, val sourceRef: String? = null, val sourceUrl: String? = null, val pinnedAt: Long? = null, val archivedAt: Long? = null, val trashedAt: Long? = null, val privateAt: Long? = null, val supersededOpIds: List<String> = emptyList(), val machine: Boolean? = null, val origin: String? = null, val affectedNoteIds: List<String> = emptyList())
private val random = SecureRandom()
@Synchronized fun uuidV7(now: Long = System.currentTimeMillis()): String {
 require(now in 0..0xffffffffffffL)
 return UUID((now shl 16) or 0x7000L or random.nextInt(4096).toLong(), (random.nextLong() and 0x3fffffffffffffffL) or Long.MIN_VALUE).toString()
}
fun compactOutbox(ops: List<OutboxOperation>): List<OutboxOperation> {
 val result = mutableListOf<OutboxOperation>()
 for (op in ops) {
  val create = result.indexOfLast { it.noteId == op.noteId && it.type == "note.create" }
  if (op.type == "note.flags" && create >= 0) { val old=result[create]; result[create]=old.copy(at=op.at,pinned=op.pinned?:old.pinned,archived=op.archived?:old.archived,trashed=op.trashed?:old.trashed,privateNote=op.privateNote?:old.privateNote,pinnedAt=if(op.pinned!=null) op.pinnedAt?:op.at else old.pinnedAt,archivedAt=if(op.archived!=null) op.archivedAt?:op.at else old.archivedAt,trashedAt=if(op.trashed!=null) op.trashedAt?:op.at else old.trashedAt,privateAt=if(op.privateNote!=null) op.privateAt?:op.at else old.privateAt);continue }
  if (op.type == "note.update" && create >= 0) { result[create] = result[create].copy(body = op.body); continue }
  val last = result.lastOrNull()
  if (op.type == "note.update" && last?.type == op.type && last.noteId == op.noteId) { result[result.lastIndex] = last.copy(body = op.body); continue }
  if (op.type == "note.flags") {
   val index = result.indexOfLast { it.type == op.type && it.noteId == op.noteId && (it.pinned!=null)==(op.pinned!=null) && (it.archived!=null)==(op.archived!=null) && (it.trashed!=null)==(op.trashed!=null) && (it.privateNote!=null)==(op.privateNote!=null) }
   if (index >= 0) { val old = result[index]; result[index] = old.copy(at = op.at, pinned = op.pinned ?: old.pinned, archived = op.archived ?: old.archived, trashed = op.trashed ?: old.trashed, privateNote = op.privateNote ?: old.privateNote); continue }
  }
  result += op
 }
 return result
}
