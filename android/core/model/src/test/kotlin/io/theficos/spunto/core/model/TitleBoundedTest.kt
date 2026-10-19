package io.theficos.spunto.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class TitleBoundedTest {
    @Test(timeout = 5_000)
    fun unmatchedLinkBracketsDoNotRetryEveryOpener() {
        assertEquals("[".repeat(120), titleOf("[".repeat(100_000)))
    }

    @Test(timeout = 5_000)
    fun unmatchedLinkDestinationsDoNotRescanTheSuffix() {
        assertEquals("[](".repeat(40), titleOf("[](".repeat(33_333)))
    }

    @Test(timeout = 5_000)
    fun unmatchedHtmlTagsDoNotRetryEveryOpener() {
        assertEquals("<".repeat(120), titleOf("<".repeat(100_000)))
    }

    @Test(timeout = 5_000)
    fun longBacktickRunDoesNotBacktrackThroughOpenerWidths() {
        assertEquals("", titleOf("`".repeat(100_000)))
        assertEquals("`", titleOf("`".repeat(99_999)))
    }

    @Test(timeout = 5_000)
    fun codeCloserSkipsManyShorterRunsWithoutRetryingTheirPrefixes() {
        val marker = "`".repeat(300)
        val content = "head " + ("`".repeat(299) + " x ").repeat(250) + "tail"
        assertEquals(content, titleOf("$marker$content$marker", 100_000))
    }

    @Test
    fun codeSpansPreserveUnicodeRegexLineTerminators() {
        for (separator in listOf('\u0085', '\u2028', '\u2029')) {
            val unmatched = "`left${separator}right`"
            assertEquals(unmatched, titleOf(unmatched))
            assertEquals("left${separator}right", titleOf("`left`$separator`right`"))
        }
        for (separator in listOf('\u000b', '\u000c')) {
            assertEquals("left${separator}right", titleOf("`left${separator}right`"))
        }
    }
}
