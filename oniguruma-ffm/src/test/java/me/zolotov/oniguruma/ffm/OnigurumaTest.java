package me.zolotov.oniguruma.ffm;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class OnigurumaTest {
    @Test
    void invalidPatternRaisesRuntimeException() {
        try (var oniguruma = Oniguruma.createFromResources()) {
            var exception = assertThrows(
                    RuntimeException.class,
                    () -> oniguruma.createRegex("(".getBytes(StandardCharsets.UTF_8))
            );
            assertTrue(
                    exception.getMessage().toLowerCase().contains("parenthes")
                            || exception.getMessage().toLowerCase().contains("compile"),
                    () -> "unexpected error message: " + exception.getMessage()
            );
        }
    }

    @Test
    void matchRejectsByteOffsetBeyondContentLength() {
        try (var oniguruma = Oniguruma.createFromResources()) {
            var regex = oniguruma.createRegex("a".getBytes(StandardCharsets.UTF_8));
            var text = oniguruma.createString(new byte[0]);

            var exception = assertThrows(
                    IllegalArgumentException.class,
                    () -> oniguruma.match(regex, text, 1, true, true)
            );
            assertEquals("byteOffset 1 out of range [0, 0]", exception.getMessage());

            oniguruma.freeRegex(regex);
            oniguruma.freeString(text);
        }
    }
}
