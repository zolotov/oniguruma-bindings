package me.zolotov.oniguruma

import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OnigurumaMatchingTest {
    @Test
    fun matching(): TestResult = withMatcher("[0-9]+") { matcher ->
        assertEquals(
            listOf(Capture(0, 2)),
            matcher.match("12:00pm", 0)
        )
    }

    @Test
    fun matchingFromPosition(): TestResult = withMatcher("[0-9]+") { matcher ->
        assertEquals(
            listOf(Capture(3, 5)),
            matcher.match("12:00pm", 2)
        )
    }

    @Test
    fun matchingWithGroups(): TestResult = withMatcher("([0-9]+):([0-9]+)") { matcher ->
        assertEquals(
            listOf(Capture(0, 5), Capture(0, 2), Capture(3, 5)),
            matcher.match("12:00pm", 0)
        )
    }

    @Test
    fun matchBeginPosition(): TestResult = withMatcher("\\Gbar") { matcher ->
        val noBeginMatch = matcher.match("foo bar", 4, matchBeginPosition = false, matchBeginString = true)
        assertNull(noBeginMatch)

        val beginMatch = matcher.match("foo bar", 4, matchBeginPosition = true, matchBeginString = true)
        assertEquals(listOf(Capture(4, 7)), beginMatch)
    }

    @Test
    fun matchBeginString(): TestResult = withMatcher("\\Afoo") { matcher ->
        val noBeginMatch = matcher.match("foo bar", 0, matchBeginPosition = true, matchBeginString = false)
        assertNull(noBeginMatch)

        val beginMatch = matcher.match("foo bar", 0, matchBeginPosition = true, matchBeginString = true)
        assertEquals(listOf(Capture(0, 3)), beginMatch)
    }

    @Test
    fun cyrillicMatchingSinceIndex(): TestResult = withMatcher("мир") { matcher ->
        assertEquals(
            listOf(Capture(21, 24)),
            matcher.match("привет, мир; привет, мир!", 9)
        )
    }

    @Test
    fun cyrillicMatching(): TestResult = withMatcher("мир") { matcher ->
        assertEquals(
            listOf(Capture(8, 11)),
            matcher.match("привет, мир!", 0)
        )
    }

    @Test
    fun unicodeMatching(): TestResult = withMatcher("мир") { matcher ->
        val string = "🚧🚧🚧 привет, мир 123!"
        val match = matcher.match(string, 0)!!
        assertEquals("мир", string.substring(match.first().start, match.first().end))
    }

    @Test
    fun emptyTextMatching(): TestResult = withMatcher("\\A\\z") { matcher ->
        assertEquals(
            listOf(Capture(0, 0)),
            matcher.match("", 0)
        )
    }

    @Test
    fun emptyTextMismatch(): TestResult = withMatcher(".") { matcher ->
        assertNull(matcher.match("", 0))
    }

    @Test
    fun matchNonSequentGroups(): TestResult = withMatcher(
        "^\\s*(?i:(ONBUILD)\\s+)?(?i:(ADD|ARG|CMD|COPY|ENTRYPOINT|ENV|EXPOSE|FROM|HEALTHCHECK|LABEL|MAINTAINER|RUN|SHELL|STOPSIGNAL|USER|VOLUME|WORKDIR))\\s"
    ) { matcher ->
        val string = "RUN find . -maxdepth 1 -type f -name \".*\" -exec rm \"{}\" \\;"
        val match = matcher.match(string, 0)
        assertEquals(listOf(Capture(0, 4), Capture(-1, -1), Capture(0, 3)), match)
    }

    private fun withMatcher(pattern: String, assertion: (Matcher) -> Unit): TestResult = runTest {
        createOniguruma().use { oniguruma ->
            oniguruma.createRegex(pattern.encodeToByteArray()).use { regex ->
                assertion(Matcher(oniguruma, regex))
            }
        }
    }

    /**
     * Adapts the byte-offset API to the char offsets the test data is written in, mirroring how
     * `textmate-core` consumes the bindings.
     */
    private class Matcher(private val oniguruma: Oniguruma, private val regex: OnigurumaRegex) {
        fun match(
            string: String,
            startCharOffset: Int,
            matchBeginPosition: Boolean = true,
            matchBeginString: Boolean = true,
        ): List<Capture>? {
            val stringBytes = string.encodeToByteArray()
            return oniguruma.createString(stringBytes).use { text ->
                oniguruma.match(
                    regex,
                    text,
                    byteOffsetByCharOffset(string, startCharOffset),
                    matchBeginPosition,
                    matchBeginString
                )?.let { toCaptures(stringBytes, it) }
            }
        }

        private fun toCaptures(stringBytes: ByteArray, regionOffsets: IntArray): List<Capture> {
            val captures = ArrayList<Capture>(regionOffsets.size / 2)
            for (i in regionOffsets.indices step 2) {
                val first = regionOffsets[i]
                val second = regionOffsets[i + 1]
                if (first == -1) {
                    captures.add(Capture(-1, -1))
                    continue
                }
                val start = stringBytes.decodeToString(0, first).length
                val end = start + stringBytes.decodeToString(first, second).length
                captures.add(Capture(start, end))
            }
            return captures
        }
    }

    private data class Capture(val start: Int, val end: Int)
}

internal fun byteOffsetByCharOffset(charSequence: CharSequence, charOffset: Int): Int {
    if (charOffset <= 0) {
        return 0
    }
    var result = 0
    var i = 0
    while (i < charOffset) {
        val current = charSequence[i]
        if (current.isHighSurrogate() && i + 1 < charSequence.length && charSequence[i + 1].isLowSurrogate()) {
            result += utf8Size(codePoint(current, charSequence[i + 1]))
            i++
        } else {
            result += utf8Size(current.code)
        }
        i++
    }
    return result
}

private fun codePoint(high: Char, low: Char): Int =
    (high.code - 0xD800) * 0x400 + (low.code - 0xDC00) + 0x10000

private fun utf8Size(codePoint: Int): Int = when {
    codePoint <= 0x7F -> 1
    codePoint <= 0x7FF -> 2
    codePoint <= 0xFFFF -> 3
    else -> 4
}
