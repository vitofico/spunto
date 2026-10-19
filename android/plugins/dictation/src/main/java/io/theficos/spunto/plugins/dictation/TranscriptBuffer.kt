package io.theficos.spunto.plugins.dictation

/** Replaces only the live span; final results never replace earlier committed words. */
class TranscriptBuffer(initial: String, selectionStart: Int, selectionEnd: Int = selectionStart) {
    private val original = initial
    private var received = false
    private val prefix = initial.substring(0, minOf(selectionStart,selectionEnd).coerceIn(0, initial.length))
    private val suffix = initial.substring(maxOf(selectionStart,selectionEnd).coerceIn(0, initial.length))
    private var committed = ""
    private var partial = ""
    val text: String get() = if(received) join(prefix, join(committed, partial), suffix) else original
    val settled: String get() = if(received) join(prefix, committed, suffix) else original
    val hasCapture: Boolean get() = received && (committed.isNotBlank() || partial.isNotBlank())
    val hasPartial: Boolean get() = partial.isNotBlank()
    val partialSpan: IntRange? get() { if(partial.isEmpty()) return null;val before=join(prefix,committed);val start=before.length+(if(before.isEmpty() || before.last().isWhitespace() || partial.first() in ".,!?;:") 0 else 1);return start until start+partial.length }
    fun partial(value: String): String { if(value.isBlank()) return text; received=true;partial = prepare(value.trim()); return text }
    fun final(value: String): String {
        val next = value.trim().ifEmpty { partial }
        if(next.isNotEmpty()) received=true
        committed = join(committed, prepare(next))
        partial = ""
        return text
    }
    fun commitPartial(): String = final("")
    private fun prepare(value: String): String {
        if (value.isEmpty()) return value
        val before = join(prefix, committed).trimEnd()
        return if (before.isEmpty() || before.last() in ".!?\n") value.replaceFirstChar { it.uppercaseChar() } else value
    }
    companion object {
        private fun join(vararg segments: String): String = segments.filter { it.isNotEmpty() }.fold("") { result, next ->
            if (result.isEmpty()) next else result + (if (result.last().isWhitespace() || next.first().isWhitespace() || next.first() in ".,!?;:") "" else " ") + next
        }
    }
}
