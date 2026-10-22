package io.theficos.spunto.data.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.theficos.spunto.core.model.*
import io.theficos.spunto.data.local.*
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SyncReconcileCostTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var db: SpuntoDatabase
    private lateinit var repository: NotesRepository
    private lateinit var server: MockWebServer
    private val queries = CopyOnWriteArrayList<String>()

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "reconcile-cost-${UUID.randomUUID()}.db"
        db = Room.databaseBuilder(context, SpuntoDatabase::class.java, databaseName)
            .allowMainThreadQueries()
            .setQueryCallback({ sql, _ -> queries.add(sql.replace("`", "")) }, Executor { it.run() })
            .build()
        repository = NotesRepository(db)
        server = MockWebServer().also { it.start() }
    }

    @After fun close() {
        server.shutdown()
        db.close()
        context.deleteDatabase(databaseName)
    }

    @Test fun reconciliationFreezesLatestOperationsOncePerChunk(): Unit = runBlocking {
        val notes = (1..24).map { Note(uuidV7(), "Synthetic note $it", serverRev = it) }
        for ((index, note) in notes.withIndex()) {
            repository.putNote(note)
            val operation = OutboxOperation(type = "note.flags", noteId = note.id, pinned = true, at = index.toLong())
            repository.dao.putOutbox(OutboxEntity(operation.opId, note.id, Json.encodeToString(operation), index.toLong(), sent = true))
        }
        val existingRestore = OutboxOperation(type = "note.restore", noteId = notes.last().id, body = notes.last().body)
        repository.dao.putOutbox(OutboxEntity(existingRestore.opId, existingRestore.noteId, Json.encodeToString(existingRestore), -1))
        val deleted = Note(uuidV7(), "Excluded tombstoned fixture")
        repository.putNote(deleted)
        repository.dao.putTombstone(TombstoneEntity(deleted.id, 1))
        repository.dao.putState(SyncStateEntity(epoch = "old", cursor = "old.9"))
        val restored = mutableListOf<OutboxOperation>()
        var firstPush = true
        var reads = 0
        var expectedSuperseded = emptyMap<String?, List<String>>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                fun response(value: String) = MockResponse().setBody(value)
                return when (request.requestUrl!!.encodedPath) {
                    "/api/v1/meta" -> response("""{"sync_epoch":"new"}""")
                    "/api/v1/sync/reconcile" -> {
                        val manifest = Json.parseToJsonElement(request.body.readUtf8()).jsonObject["notes"]!!.jsonArray
                        assertEquals(notes.map { it.id }.toSet(), manifest.map { it.jsonObject["id"]!!.jsonPrimitive.content }.toSet())
                        runBlocking {
                            // A network response cannot freeze an older body or resurrect a newly removed note.
                            repository.update(notes.first().id, "Owner typing during reconciliation")
                            repository.dao.putTombstone(TombstoneEntity(notes[1].id, 2))
                            expectedSuperseded = repository.dao.outbox()
                                .filter { Json.decodeFromString<OutboxOperation>(it.payload).type in listOf("note.create", "note.update", "note.flags") }
                                .groupBy { it.noteId }.mapValues { (_, rows) -> rows.map { it.opId } }
                        }
                        queries.clear()
                        response(buildJsonObject {
                            put("epoch", "new")
                            put("restore_ids", JsonArray((notes.map { it.id } + notes.first().id + deleted.id).map(::JsonPrimitive)))
                        }.toString())
                    }
                    "/api/v1/sync/push" -> {
                        val wire = Json.parseToJsonElement(request.body.readUtf8()).jsonObject["ops"]!!.jsonArray
                        if (firstPush) {
                            reads = queries.count { it.startsWith("SELECT * FROM outbox ORDER BY") }
                            firstPush = false
                        }
                        val operations = runBlocking {
                            val queued = repository.dao.outbox().associateBy { it.opId }
                            wire.map { Json.decodeFromString<OutboxOperation>(queued.getValue(it.jsonObject["op_id"]!!.jsonPrimitive.content).payload) }
                        }
                        restored += operations.filter { it.type == "note.restore" }
                        response(buildJsonObject {
                            put("epoch", "new")
                            put("results", JsonArray(operations.map { operation -> buildJsonObject {
                                put("op_id", operation.opId); put("status", "applied"); put("version", 1)
                            } }))
                        }.toString())
                    }
                    "/api/v1/sync/pull" -> response("""{"epoch":"new","cursor":"new.1","has_more":false,"plugins_rev":0,"items":[]}""")
                    "/api/v1/plugins", "/api/v1/plugins/manifest" -> response("""{"plugins":[],"plugins_rev":0}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        val engine = SyncEngine(repository, { AuthConfig(server.url("/").toString(), "synthetic", "d", "Phone", true) })
        assertEquals(SyncStatus.Complete(0), engine.sync())
        println("reconcile_notes=${notes.size} full_outbox_reads_before_first_push=$reads")
        assertEquals(2, reads)
        assertEquals(notes.size - 1, restored.size)
        assertEquals(restored.size, restored.map { it.noteId }.distinct().size)
        assertEquals("Owner typing during reconciliation", restored.single { it.noteId == notes.first().id }.body)
        assertTrue(restored.none { it.noteId == notes[1].id || it.noteId == deleted.id })
        assertEquals(existingRestore, restored.single { it.noteId == notes.last().id })
        for (operation in restored.filter { it.opId != existingRestore.opId }) {
            assertEquals(expectedSuperseded[operation.noteId].orEmpty(), operation.supersededOpIds)
        }
    }
}
