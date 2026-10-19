package io.theficos.spunto.data.local

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import org.junit.Assert.*
import org.junit.Test

/** Real BM25 and real Job cancellation; no mutable production hooks or timing assertions. */
class SearchRankingTest {
    private fun info(titleHits: Int = 1, bodyHits: Int = 0): ByteArray {
        val values = intArrayOf(
            1, 2, 100, 10, 100, 10, 100,
            titleHits, titleHits * 10, if (titleHits == 0) 0 else 10,
            bodyHits, bodyHits * 10, if (bodyHits == 0) 0 else 10,
        )
        return ByteBuffer.allocate(values.size * Int.SIZE_BYTES)
            .order(ByteOrder.nativeOrder()).apply { values.forEach { putInt(it) } }.array()
    }

    private fun row(id: String, updated: Long, bytes: ByteArray = info()) = RankedNote(
        NoteEntity(
            id = id, body = "Synthetic scoring body", title = "Synthetic scoring title",
            foldedTitle = "synthetic scoring title", foldedBody = "synthetic scoring body",
            createdAt = 1, updatedAt = updated,
        ), bytes,
    )

    @Test fun eachCandidateIsScoredOnceAndInputRowsAreUnchanged() {
        val rows = (0 until 32).map { index ->
            row("synthetic-${31 - index}", 100L + index, info(1 + index % 4, index % 3))
        }.toMutableList()
        val original = rows.toList()
        val originalBytes = rows.map { it.info.copyOf() }
        val expected = rows.sortedWith(
            compareByDescending<RankedNote> { bm25(it.info) }.thenByDescending { it.note.updatedAt },
        ).map { it.note }
        var evaluations = 0
        val actual = rankSearchRows(rows, {}, { bytes -> evaluations++; bm25(bytes) })
        assertEquals(32, evaluations)
        assertEquals(expected, actual)
        rows.indices.forEach { index ->
            assertSame(original[index], rows[index])
            assertArrayEquals(originalBytes[index], rows[index].info)
        }
        evaluations = 0
        assertEquals(listOf(rows.first().note), rankSearchRows(listOf(rows.first()), {}, { bytes -> evaluations++; bm25(bytes) }))
        assertEquals(1, evaluations)
        evaluations = 0
        assertTrue(rankSearchRows(emptyList(), {}, { bytes -> evaluations++; bm25(bytes) }).isEmpty())
        assertEquals(0, evaluations)
    }

    @Test fun titleWeightAndScorePriorityStayUnchanged() {
        // pc/n/a/l/x: same document frequency and normalized field lengths.
        fun exact(vararg values: Int) = ByteBuffer.allocate(values.size * Int.SIZE_BYTES)
            .order(ByteOrder.nativeOrder()).apply { values.forEach { putInt(it) } }.array()
        val titleInfo = exact(1, 2, 100, 10, 100, 10, 100, 1, 1, 1, 0, 0, 0)
        val bodyInfo = exact(1, 2, 100, 10, 100, 10, 100, 0, 0, 0, 1, 1, 1)
        assertTrue(bm25(bodyInfo) > 0)
        assertEquals(2.0 * bm25(bodyInfo), bm25(titleInfo), 1e-12)
        val body = row("body-newer", 300, bodyInfo)
        val title = row("title-older", 100, titleInfo)
        assertEquals(listOf(title.note, body.note), rankSearchRows(listOf(body, title), {}))
    }

    @Test fun updatedThenStableInputTiesRemainExact() {
        val older = row("middle-id", 100)
        val highId = row("z-id", 300)
        val lowId = row("a-id", 300)
        val inputs = listOf(listOf(older, highId, lowId), listOf(lowId, older, highId), listOf(highId, lowId, older))
        for (input in inputs) {
            val expected = input.filter { it.note.updatedAt == 300L }.map { it.note } + older.note
            assertEquals(expected, rankSearchRows(input, {}))
        }
    }

    @Test fun cancelledJobStopsScoringWithoutReturningPartialResults() {
        val job = Job()
        val rows = (0 until 32).map { row("cancel-$it", it.toLong(), info(1 + it % 4)) }
        val original = rows.toList()
        val originalBytes = rows.map { it.info.copyOf() }
        var evaluations = 0
        var result: List<NoteEntity>? = null
        try {
            assertThrows(CancellationException::class.java) {
                result = rankSearchRows(rows, { job.ensureActive() }, { bytes ->
                    val score = bm25(bytes)
                    if (++evaluations == 3) job.cancel()
                    score
                })
            }
            assertEquals(3, evaluations)
            assertNull(result)
            rows.indices.forEach {
                assertSame(original[it], rows[it])
                assertArrayEquals(originalBytes[it], rows[it].info)
            }
        } finally { job.cancel() }
    }

    @Test fun cancellationAfterLastSingletonScoreCannotEscape() {
        val job = Job()
        var evaluations = 0
        var result: List<NoteEntity>? = null
        try {
            assertThrows(CancellationException::class.java) {
                result = rankSearchRows(listOf(row("singleton", 100)), { job.ensureActive() }, { bytes ->
                    evaluations++
                    val score = bm25(bytes)
                    job.cancel()
                    score
                })
            }
            assertEquals(1, evaluations)
            assertNull(result)
            evaluations = 0
            assertThrows(CancellationException::class.java) {
                result = rankSearchRows(emptyList(), { job.ensureActive() }, { bytes -> evaluations++; bm25(bytes) })
            }
            assertEquals(0, evaluations)
            assertNull(result)
        } finally { job.cancel() }
    }
}
