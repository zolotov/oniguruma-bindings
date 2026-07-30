package me.zolotov.oniguruma

import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OnigurumaTest {
    @Test
    fun invalidPatternRaisesOnigurumaException(): TestResult = runTest {
        createOniguruma().use { oniguruma ->
            val exception = assertFailsWith<OnigurumaException> {
                oniguruma.createRegex("(".encodeToByteArray())
            }
            // Assert on the oniguruma diagnostic itself, not just on our own wrapper text, so a
            // backend that swallows the detail fails the test.
            assertTrue(
                exception.message.orEmpty().lowercase().contains("parenthes"),
                "error message should carry the oniguruma diagnostic, got: ${exception.message}"
            )
        }
    }

    @Test
    fun matchRejectsByteOffsetBeyondContentLength(): TestResult = runTest {
        createOniguruma().use { oniguruma ->
            oniguruma.createRegex("a".encodeToByteArray()).use { regex ->
                oniguruma.createString(ByteArray(0)).use { text ->
                    val exception = assertFailsWith<IllegalArgumentException> {
                        oniguruma.match(regex, text, 1, matchBeginPosition = true, matchBeginString = true)
                    }
                    assertEquals("byteOffset 1 out of range [0, 0]", exception.message)
                }
            }
        }
    }
}
