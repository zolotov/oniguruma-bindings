package me.zolotov.oniguruma.ffm;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OnigurumaMatchingTest {
    @Test
    void matching() {
        withMatcher("[0-9]+", matcher -> assertEquals(
                List.of(new Capture(0, 2)),
                matcher.match("12:00pm", 0)
        ));
    }

    @Test
    void matchingFromPosition() {
        withMatcher("[0-9]+", matcher -> assertEquals(
                List.of(new Capture(3, 5)),
                matcher.match("12:00pm", 2)
        ));
    }

    @Test
    void matchingWithGroups() {
        withMatcher("([0-9]+):([0-9]+)", matcher -> assertEquals(
                List.of(new Capture(0, 5), new Capture(0, 2), new Capture(3, 5)),
                matcher.match("12:00pm", 0)
        ));
    }

    @Test
    void matchBeginPosition() {
        withMatcher("\\Gbar", matcher -> {
            var noBeginMatch = matcher.match("foo bar", 4, false, true);
            assertEquals(List.of(), noBeginMatch);

            var beginMatch = matcher.match("foo bar", 4, true, true);
            assertEquals(List.of(new Capture(4, 7)), beginMatch);
        });
    }

    @Test
    void matchBeginString() {
        withMatcher("\\Afoo", matcher -> {
            var noBeginMatch = matcher.match("foo bar", 0, true, false);
            assertEquals(List.of(), noBeginMatch);

            var beginMatch = matcher.match("foo bar", 0, true, true);
            assertEquals(List.of(new Capture(0, 3)), beginMatch);
        });
    }

    @Test
    void cyrillicMatchingSinceIndex() {
        withMatcher("мир", matcher -> assertEquals(
                List.of(new Capture(21, 24)),
                matcher.match("привет, мир; привет, мир!", 9)
        ));
    }

    @Test
    void cyrillicMatching() {
        withMatcher("мир", matcher -> assertEquals(
                List.of(new Capture(8, 11)),
                matcher.match("привет, мир!", 0)
        ));
    }

    @Test
    void unicodeMatching() {
        withMatcher("мир", matcher -> {
            var string = "\uD83D\uDEA7\uD83D\uDEA7\uD83D\uDEA7 привет, мир 123!";
            var match = matcher.match(string, 0);
            assertEquals("мир", string.substring(match.getFirst().start(), match.getFirst().end()));
        });
    }

    @Test
    void emptyTextMatching() {
        withMatcher("\\A\\z", matcher -> assertEquals(
                List.of(new Capture(0, 0)),
                matcher.match("", 0)
        ));
    }

    @Test
    void emptyTextMismatch() {
        withMatcher(".", matcher -> assertEquals(
                List.of(),
                matcher.match("", 0)
        ));
    }

    @Test
    void matchNonSequentGroups() {
        withMatcher(
                "^\\s*(?i:(ONBUILD)\\s+)?(?i:(ADD|ARG|CMD|COPY|ENTRYPOINT|ENV|EXPOSE|FROM|HEALTHCHECK|LABEL|MAINTAINER|RUN|SHELL|STOPSIGNAL|USER|VOLUME|WORKDIR))\\s",
                matcher -> {
                    var string = "RUN find . -maxdepth 1 -type f -name \".*\" -exec rm \"{}\" \\;";
                    var match = matcher.match(string, 0);
                    assertEquals(List.of(new Capture(0, 4), new Capture(-1, -1), new Capture(0, 3)), match);
                }
        );
    }

    private static void withMatcher(String pattern, MatcherAssertion assertion) {
        try (var oniguruma = Oniguruma.createFromResources()) {
            var regex = oniguruma.createRegex(pattern.getBytes(StandardCharsets.UTF_8));
            try {
                assertion.run(new Matcher(oniguruma, regex));
            } finally {
                oniguruma.freeRegex(regex);
            }
        }
    }

    @FunctionalInterface
    private interface MatcherAssertion {
        void run(Matcher matcher);
    }

    private record Matcher(Oniguruma oniguruma, OnigurumaRegex regex) {
        private List<Capture> match(String string, int startCharOffset) {
                return match(string, startCharOffset, true, true);
            }

            private List<Capture> match(
                    String string,
                    int startCharOffset,
                    boolean matchBeginPosition,
                    boolean matchBeginString
            ) {
                var stringBytes = string.getBytes(StandardCharsets.UTF_8);
                var text = oniguruma.createString(stringBytes);
                try {
                    var result = oniguruma.match(
                            regex,
                            text,
                            byteOffsetByCharOffset(string, startCharOffset),
                            matchBeginPosition,
                            matchBeginString
                    );
                    return toCaptures(stringBytes, result);
                } finally {
                    oniguruma.freeString(text);
                }
            }

            private static List<Capture> toCaptures(byte[] stringBytes, int[] regionOffsets) {
                var captures = new ArrayList<Capture>(regionOffsets.length / 2);
                for (int i = 0; i < regionOffsets.length; i += 2) {
                    var first = regionOffsets[i];
                    var second = regionOffsets[i + 1];
                    if (first == -1) {
                        captures.add(new Capture(-1, -1));
                        continue;
                    }
                    var start = new String(stringBytes, 0, first, StandardCharsets.UTF_8).length();
                    var end = start + new String(stringBytes, first, second - first, StandardCharsets.UTF_8).length();
                    captures.add(new Capture(start, end));
                }
                return captures;
            }
        }

    private record Capture(int start, int end) {
    }

    private static int byteOffsetByCharOffset(CharSequence charSequence, int charOffset) {
        if (charOffset <= 0) {
            return 0;
        }
        var result = 0;
        var i = 0;
        while (i < charOffset) {
            var current = charSequence.charAt(i);
            if (Character.isHighSurrogate(current)
                    && i + 1 < charSequence.length()
                    && Character.isLowSurrogate(charSequence.charAt(i + 1))) {
                result += utf8Size(Character.toCodePoint(current, charSequence.charAt(i + 1)));
                i++;
            } else {
                result += utf8Size(current);
            }
            i++;
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
