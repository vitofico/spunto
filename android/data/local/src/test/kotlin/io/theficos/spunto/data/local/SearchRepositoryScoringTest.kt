package io.theficos.spunto.data.local

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import io.theficos.spunto.core.model.Note
import io.theficos.spunto.core.model.searchQuery
import io.theficos.spunto.core.model.uuidV7
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual FTS and repository results on unique files; never the product database or a fake DAO. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SearchRepositoryScoringTest {
    @get:Rule val timeout = org.junit.rules.Timeout(10, TimeUnit.MINUTES)
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var db: SpuntoDatabase
    private lateinit var repository: NotesRepository

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "search-scoring-${UUID.randomUUID()}.db"
        db = Room.databaseBuilder(context, SpuntoDatabase::class.java, databaseName)
            .allowMainThreadQueries().build()
        repository = NotesRepository(db)
    }

    @After fun close() {
        if (this::db.isInitialized) db.close()
        if (this::context.isInitialized && this::databaseName.isInitialized) context.deleteDatabase(databaseName)
    }

    @Test fun textSearchPreservesLegacyRankAllResultsAndMetadata(): Unit = runBlocking {
        val base = 1_700_000_000_000L
        val ancestor = Note(uuidV7(), "Synthetic conflict ancestor", createdAt = base, updatedAt = base, serverRev = 1)
        val notes = (1..128).map { index ->
            val body = if (index % 2 == 0) {
                "rankprobe title $index\n\nSynthetic paragraph #Work\n\n`#Hidden`"
            } else {
                "Synthetic title $index\n\nrankprobe paragraph #Work/Child\n\n```\n#Hidden\n```"
            }
            val machine = index % 13 == 0
            Note(
                uuidV7(), body, createdAt = base - index * 1000L, updatedAt = base + index * 1000L,
                serverRev = index, archived = index % 5 == 0, privateNote = index % 7 == 0,
                pinned = index % 3 == 0, machine = machine,
                conflictOf = if (index % 17 == 0) ancestor.id else null,
                sourceKind = if (machine) "plugin" else "android",
                sourceRef = "synthetic-$index", sourceUrl = "https://synthetic.invalid/$index",
                origin = if (machine) "plugin" else "owner", updatedViaAgent = index % 11 == 0,
                pinnedAt = base + index, archivedAt = base + index + 1,
                privateAt = base + index + 2, pending = false,
            )
        }
        val trash = Note(uuidV7(), "rankprobe excluded trash", createdAt = base, updatedAt = base + 1_000_000, trashed = true, trashedAt = base + 1_000_000)
        val unrelated = (1..3).map { Note(uuidV7(), "Unrelated synthetic fixture $it", createdAt = base, updatedAt = base + 2_000_000 + it) }
        db.withTransaction { (notes + trash + unrelated + ancestor).forEach { repository.putNote(it) } }
        assertEquals(128, notes.map { it.updatedAt }.distinct().size)
        assertTrue(notes.any { it.privateNote } && notes.any { it.archived } && notes.any { it.machine })
        val expression = requireNotNull(searchQuery("rankprobe").expression)
        val candidates = repository.dao.search(expression)
        assertEquals(128, candidates.size)
        // Unique updatedAt gives a total comparator order. No writes occur
        // between these two unordered SQL reads; complete ties are tested only
        // on explicit helper input permutations in SearchRankingTest.
        val legacy = candidates.sortedWith(
            compareByDescending<RankedNote> { bm25(it.info) }.thenByDescending { it.note.updatedAt },
        ).map { it.note.note() }
        val actual = repository.search("rankprobe")
        assertEquals(legacy.map { it.id }, actual.map { it.id })
        assertEquals(legacy, actual)
        assertEquals(notes.associateBy { it.id }, actual.associateBy { it.id })
        assertEquals(128, actual.size)
        assertFalse(actual.any { it.id == trash.id || it.trashed })
        assertTrue(repository.dao.outbox().isEmpty())
    }

    @Test fun labelOnlyAndCombinedKeepBodySemanticsAndVisibility(): Unit = runBlocking {
        val base = 1_700_000_000_000L
        fun fixture(index: Int, body: String, archived: Boolean = false, privateNote: Boolean = false, trashed: Boolean = false) =
            Note(uuidV7(), body, createdAt = base, updatedAt = base + index, serverRev = index,
                archived = archived, privateNote = privateNote, trashed = trashed,
                sourceKind = "import", sourceRef = "synthetic-label-$index", origin = "external")
        val exact = fixture(1, "Needle exact #Work")
        val child = fixture(2, "Needle descendant #Work/Child #Topic")
        val archive = fixture(3, "Needle archive #Work #Topic", archived = true)
        val privateMember = fixture(4, "Needle private #Work/Secret #Topic", privateNote = true)
        val others = listOf(
            fixture(5, "Needle unrelated #Other"), fixture(6, "Needle plain Work Child Topic"),
            fixture(7, "Needle inline code `#Work` `#Topic`"),
            fixture(8, "Needle fence\n\n```\n#Work #Topic\n```"),
            fixture(9, "Needle escaped \\#Work \\#Topic"),
            fixture(10, "Needle trash #Work #Topic", trashed = true),
        )
        val all = listOf(exact, child, archive, privateMember) + others
        db.withTransaction { all.forEach { repository.putNote(it) } }
        val expected = listOf(privateMember, archive, child, exact)
        assertEquals(expected, repository.search("#work"))
        assertEquals(listOf(privateMember, archive, child), repository.search("#work #topic"))
        for (query in listOf("needle #work", "needle #work #topic")) {
            val selected = if (query.endsWith("#topic")) expected.take(3) else expected
            val actual = repository.search(query)
            assertEquals(selected.associateBy { it.id }, actual.associateBy { it.id })
            assertEquals(selected.size, actual.size)
        }
        for (query in listOf("", "   ", "!!!", "\"\"")) assertTrue(repository.search(query).isEmpty())
        assertTrue(repository.dao.outbox().isEmpty())
        assertEquals(all.associateBy { it.id }, repository.dao.all().map { it.note() }.associateBy { it.id })
    }
}
