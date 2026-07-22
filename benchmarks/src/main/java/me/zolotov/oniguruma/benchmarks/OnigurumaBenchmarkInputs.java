package me.zolotov.oniguruma.benchmarks;

import java.nio.charset.StandardCharsets;

public final class OnigurumaBenchmarkInputs {
    private static final byte[] PATTERN = "[0-9]+".getBytes(StandardCharsets.UTF_8);
    private static final byte[] LARGE_PATTERN = buildLargePattern();
    private static final byte[] INVALID_PATTERN = "(unclosed[".getBytes(StandardCharsets.UTF_8);
    private static final byte[] SMALL_TEXT = "🚧🚧🚧 привет, мир 123!".getBytes(StandardCharsets.UTF_8);
    private static final byte[] LARGE_TEXT = buildLargeText();

    private OnigurumaBenchmarkInputs() {
    }

    public static byte[] pattern() {
        return PATTERN.clone();
    }

    public static byte[] largePattern() {
        return LARGE_PATTERN.clone();
    }

    public static byte[] invalidPattern() {
        return INVALID_PATTERN.clone();
    }

    public static byte[] smallText() {
        return SMALL_TEXT.clone();
    }

    public static byte[] largeText() {
        return LARGE_TEXT.clone();
    }

    /**
     * A keyword alternation shaped like the multi-kilobyte keyword lists in real textmate
     * grammars, ~8 KiB. Compared with {@link #pattern()} this shifts the weight from fixed
     * per-call binding overhead towards per-byte costs (pattern copying, validation) and the
     * compiler itself.
     */
    private static byte[] buildLargePattern() {
        StringBuilder builder = new StringBuilder("\\b(?:");
        for (int i = 0; builder.length() < 8 * 1024; i++) {
            if (i > 0) {
                builder.append('|');
            }
            builder.append("keyword").append(i);
        }
        return builder.append(")\\b").toString().getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] buildLargeText() {
        StringBuilder builder = new StringBuilder();
        while (builder.length() < 64 * 1024) {
            builder.append("val variable = listOf(1, 2, 3).map { it * it } // a typical line of source code\n");
        }
        return builder.toString().getBytes(StandardCharsets.UTF_8);
    }
}
