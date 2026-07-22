package me.zolotov.oniguruma.benchmarks;

import java.nio.charset.StandardCharsets;

public final class OnigurumaBenchmarkInputs {
    private static final byte[] PATTERN = "[0-9]+".getBytes(StandardCharsets.UTF_8);
    private static final byte[] INVALID_PATTERN = "(unclosed[".getBytes(StandardCharsets.UTF_8);
    private static final byte[] SMALL_TEXT = "🚧🚧🚧 привет, мир 123!".getBytes(StandardCharsets.UTF_8);
    private static final byte[] LARGE_TEXT = buildLargeText();

    private OnigurumaBenchmarkInputs() {
    }

    public static byte[] pattern() {
        return PATTERN.clone();
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

    private static byte[] buildLargeText() {
        StringBuilder builder = new StringBuilder();
        while (builder.length() < 64 * 1024) {
            builder.append("val variable = listOf(1, 2, 3).map { it * it } // a typical line of source code\n");
        }
        return builder.toString().getBytes(StandardCharsets.UTF_8);
    }
}
