package me.zolotov.oniguruma.jni;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OnigurumaTest {
    @Test
    void matchRejectsByteOffsetOutOfRange() {
        Oniguruma oniguruma = Oniguruma.createFromResources();
        long regexPtr = oniguruma.createRegex("a".getBytes(StandardCharsets.UTF_8));
        long textPtr = oniguruma.createString("abc".getBytes(StandardCharsets.UTF_8));
        try {
            assertThrows(IllegalArgumentException.class,
                    () -> oniguruma.match(regexPtr, textPtr, 4, true, true));
            assertThrows(IllegalArgumentException.class,
                    () -> oniguruma.match(regexPtr, textPtr, -1, true, true));
        } finally {
            oniguruma.freeString(textPtr);
            oniguruma.freeRegex(regexPtr);
        }
    }

    @Test
    void matching() {
        withMatcher("[0-9]+", matcher ->
                assertEquals(List.of(new Capture(0, 2)), matcher.match("12:00pm", 0))
        );
    }

    @Test
    void matchingFromPosition() {
        withMatcher("[0-9]+", matcher ->
                assertEquals(List.of(new Capture(3, 5)), matcher.match("12:00pm", 2))
        );
    }

    @Test
    void matchingWithGroups() {
        withMatcher("([0-9]+):([0-9]+)", matcher ->
                assertEquals(
                        List.of(new Capture(0, 5), new Capture(0, 2), new Capture(3, 5)),
                        matcher.match("12:00pm", 0)
                )
        );
    }

    @Test
    void matchBeginPosition() {
        withMatcher("\\Gbar", matcher -> {
            List<Capture> noBeginMatch = matcher.match("foo bar", 4, false, true);
            assertEquals(List.of(), noBeginMatch);

            List<Capture> beginMatch = matcher.match("foo bar", 4, true, true);
            assertEquals(List.of(new Capture(4, 7)), beginMatch);
        });
    }

    @Test
    void matchBeginString() {
        withMatcher("\\Afoo", matcher -> {
            List<Capture> noBeginMatch = matcher.match("foo bar", 0, true, false);
            assertEquals(List.of(), noBeginMatch);

            List<Capture> beginMatch = matcher.match("foo bar", 0, true, true);
            assertEquals(List.of(new Capture(0, 3)), beginMatch);
        });
    }

    @Test
    void cyrillicMatchingSinceIndex() {
        withMatcher("мир", matcher ->
                assertEquals(
                        List.of(new Capture(21, 24)),
                        matcher.match("привет, мир; привет, мир!", 9)
                )
        );
    }

    @Test
    void cyrillicMatching() {
        withMatcher("мир", matcher ->
                assertEquals(
                        List.of(new Capture(8, 11)),
                        matcher.match("привет, мир!", 0)
                )
        );
    }

    @Test
    void unicodeMatching() {
        withMatcher("мир", matcher -> {
            String string = "\uD83D\uDEA7\uD83D\uDEA7\uD83D\uDEA7 привет, мир 123!";
            List<Capture> match = matcher.match(string, 0);
            assertEquals("мир", string.substring(match.get(0).start(), match.get(0).end()));
        });
    }

    @Test
    void matchNonSequentGroups() {
        withMatcher(
                "^\\s*(?i:(ONBUILD)\\s+)?(?i:(ADD|ARG|CMD|COPY|ENTRYPOINT|ENV|EXPOSE|FROM|HEALTHCHECK|LABEL|MAINTAINER|RUN|SHELL|STOPSIGNAL|USER|VOLUME|WORKDIR))\\s",
                matcher -> {
                    String string = "RUN find . -maxdepth 1 -type f -name \".*\" -exec rm \"{}\" \\;";
                    List<Capture> match = matcher.match(string, 0);
                    assertEquals(
                            List.of(new Capture(0, 4), new Capture(-1, -1), new Capture(0, 3)),
                            match
                    );
                }
        );
    }

    private void withMatcher(String pattern, Consumer<Matcher> block) {
        Oniguruma oniguruma = Oniguruma.createFromResources();
        long regexPtr = oniguruma.createRegex(pattern.getBytes(StandardCharsets.UTF_8));
        try {
            block.accept(new Matcher(oniguruma, regexPtr));
        } finally {
            oniguruma.freeRegex(regexPtr);
        }
    }

    static final class Matcher {
        private final Oniguruma oniguruma;
        private final long regexPtr;

        Matcher(Oniguruma oniguruma, long regexPtr) {
            this.oniguruma = oniguruma;
            this.regexPtr = regexPtr;
        }

        List<Capture> match(String string, int startCharOffset) {
            return match(string, startCharOffset, true, true);
        }

        List<Capture> match(
                String string,
                int startCharOffset,
                boolean matchBeginPosition,
                boolean matchBeginString
        ) {
            byte[] stringBytes = string.getBytes(StandardCharsets.UTF_8);
            long textPtr = oniguruma.createString(stringBytes);
            try {
                int[] rawMatch = oniguruma.match(
                        regexPtr,
                        textPtr,
                        byteOffsetByCharOffset(string, startCharOffset),
                        matchBeginPosition,
                        matchBeginString
                );
                if (rawMatch == null) {
                    return List.of();
                }

                List<Capture> captures = new ArrayList<>(rawMatch.length / 2);
                for (int i = 0; i < rawMatch.length; i += 2) {
                    int first = rawMatch[i];
                    int second = rawMatch[i + 1];
                    if (first == -1) {
                        captures.add(new Capture(-1, -1));
                    } else {
                        int start = new String(stringBytes, 0, first, StandardCharsets.UTF_8).length();
                        int end = start + new String(stringBytes, first, second - first, StandardCharsets.UTF_8).length();
                        captures.add(new Capture(start, end));
                    }
                }
                return captures;
            } finally {
                oniguruma.freeString(textPtr);
            }
        }
    }

    record Capture(int start, int end) {
    }

    private static int byteOffsetByCharOffset(CharSequence charSequence, int charOffset) {
        if (charOffset <= 0) {
            return 0;
        }

        int result = 0;
        int index = 0;
        while (index < charOffset) {
            char current = charSequence.charAt(index);
            if (Character.isHighSurrogate(current)
                    && index + 1 < charSequence.length()
                    && Character.isLowSurrogate(charSequence.charAt(index + 1))) {
                result += utf8Size(Character.toCodePoint(current, charSequence.charAt(index + 1)));
                index++;
            } else {
                result += utf8Size(current);
            }
            index++;
        }
        return result;
    }

    private static int utf8Size(int codePoint) {
        if (codePoint <= 0x7F) {
            return 1;
        }
        if (codePoint <= 0x7FF) {
            return 2;
        }
        if (codePoint <= 0xFFFF) {
            return 3;
        }
        return 4;
    }
}
