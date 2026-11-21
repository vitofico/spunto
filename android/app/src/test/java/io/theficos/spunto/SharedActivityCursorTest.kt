package io.theficos.spunto

import io.theficos.spunto.data.sync.SharedEvent
import org.junit.Assert.*
import org.junit.Test

class SharedActivityCursorTest {
    private fun event(id: String, time: Long) = SharedEvent(id, "note_created", "space", "Team", "note", time, false)

    @Test fun lateCommitsAndTiedTimestampsAlertOnce() {
        val recent = event("b", 10)
        val late = event("a", 8)
        val tied = event("c", 10)
        val cursor = ActivityCursor().advance(listOf(recent))
        assertFalse(cursor.unseen(recent))
        assertTrue(cursor.unseen(late))
        assertTrue(cursor.unseen(tied))
        val next = cursor.advance(listOf(tied, recent, late))
        assertFalse(next.unseen(late))
        assertFalse(next.unseen(tied))
    }

    @Test fun oldRowsResurfacingAfterRevocationStayQuiet() {
        val page = (1L..50L).map { event("id-$it", it) }
        val cursor = ActivityCursor().advance(page)
        assertFalse(cursor.unseen(event("older", 0)))
        assertTrue(cursor.unseen(event("tied", 1)))
        val next = cursor.advance(page.drop(1) + event("new", 51))
        assertFalse(next.receipts.containsKey("id-1"))
    }
}
