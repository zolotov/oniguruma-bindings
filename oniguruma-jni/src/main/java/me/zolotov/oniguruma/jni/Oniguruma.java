package me.zolotov.oniguruma.jni;

import java.nio.file.Path;

public final class Oniguruma {
    private Oniguruma() {
    }

    public static Oniguruma createFromResources() {
        OnigurumaLoader.loadFromResources();
        return new Oniguruma();
    }

    public static Oniguruma createFromFile(Path path) {
        OnigurumaLoader.loadFromFile(path);
        return new Oniguruma();
    }

    /**
     * Searches {@code textPtr} from {@code byteOffset}.
     *
     * @throws IllegalArgumentException if {@code byteOffset} is outside {@code [0, length]} of the
     *                                  string behind {@code textPtr}. This matches the
     *                                  {@code oniguruma-ffm} binding's contract.
     */
    public native int[] match(
            long regexPtr,
            long textPtr,
            int byteOffset,
            boolean matchBeginPosition,
            boolean matchBeginString
    );

    /**
     * Compiles a pattern. {@code pattern} must be valid UTF-8: the bytes are handed to oniguruma
     * without validation, and malformed input makes matching against the resulting regex
     * undefined. This matches the {@code oniguruma-ffm} binding's contract.
     */
    public native long createRegex(byte[] pattern);

    public native void freeRegex(long regexPtr);

    /**
     * Copies a subject string into native memory. {@code utf8Content} must be valid UTF-8: the
     * bytes are handed to oniguruma without validation, and matching against malformed input is
     * undefined. This matches the {@code oniguruma-ffm} binding's contract.
     */
    public native long createString(byte[] utf8Content);

    public native void freeString(long textPtr);
}
