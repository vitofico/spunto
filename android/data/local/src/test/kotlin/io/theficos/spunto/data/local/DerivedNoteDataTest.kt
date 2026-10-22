package io.theficos.spunto.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.theficos.spunto.core.model.Note
import io.theficos.spunto.core.model.uuidV7
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import kotlin.system.measureNanoTime
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DerivedNoteDataTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var db: SpuntoDatabase
    private lateinit var repository: NotesRepository
    private val queries = CopyOnWriteArrayList<String>()

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "derived-note-${UUID.randomUUID()}.db"
        db = Room.databaseBuilder(context, SpuntoDatabase::class.java, databaseName)
            .allowMainThreadQueries()
            .setQueryCallback({ sql, _ -> queries.add(sql.replace("`", "")) }, Executor { it.run() })
            .build()
        repository = NotesRepository(db)
    }

    @After fun close() {
        db.close()
        context.deleteDatabase(databaseName)
    }

    @Test fun metadataUpdatesPreserveDerivedDataAndHistory(): Unit = runBlocking {
        val body = "# Synthetic café\n\n" + (1..600).joinToString("\n\n") {
            "[ ] Synthetic task $it #Work/Child with **emphasis** and `#Hidden`"
        }
        val original = Note(uuidV7(), body, serverRev = 4)
        repository.putNote(original)
        val before = requireNotNull(repository.dao.get(original.id))
        val labels = repository.labels().first()
        val renamed = NoteEntity.from(original.copy(title = "Changed title"), before.rowid, before)
        assertSame(before.foldedBody, renamed.foldedBody)
        assertEquals("changed title", renamed.foldedTitle)
        assertEquals(before.openTasks, renamed.openTasks)
        repeat(3) { repository.putNote(original.copy(serverRev = 5 + it, pending = true)) }
        queries.clear()
        val elapsed = measureNanoTime {
            repeat(10) { index ->
                repository.putNote(original.copy(serverRev = 10 + index, pinned = true,
                    pinnedAt = 55, privateNote = true, pending = true, syncError = "synthetic"))
            }
        }
        val labelWrites = queries.count { it.startsWith("DELETE FROM labels") || it.startsWith("INSERT OR REPLACE INTO labels") }
        println("metadata_updates=10 body_bytes=${body.toByteArray().size} elapsed_ms=${elapsed / 1_000_000.0} label_writes=$labelWrites")
        assertEquals(0, labelWrites)
        val after = requireNotNull(repository.dao.get(original.id))
        assertEquals(before.foldedBody, after.foldedBody)
        assertEquals(before.foldedTitle, after.foldedTitle)
        assertEquals(600, after.openTasks)
        assertEquals(labels, repository.labels().first())
        assertEquals(19, after.serverRev)
        assertTrue(after.pinned && after.privateNote && after.pending)
        assertEquals(55, after.pinnedAt)
        assertEquals("synthetic", after.syncError)
        assertTrue(repository.dao.history(original.id).first().isEmpty())
        assertEquals(original.id, repository.search("cafe #work").single().id)

        repository.putNote(original.copy(body = "# Updated title\n\n[ ] New task #Other"))
        val edited = requireNotNull(repository.dao.get(original.id))
        assertEquals(1, edited.openTasks)
        assertEquals(listOf("other"), repository.labels().first().map { it.key })
        assertTrue(repository.search("#work").isEmpty())
        assertEquals(original.id, repository.search("#other").single().id)
        assertEquals(body, repository.dao.history(original.id).first().single().body)
    }
}
