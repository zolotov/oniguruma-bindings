package me.zolotov.oniguruma

/**
 * Entry point to the Oniguruma bindings for the current platform.
 *
 * All coordinates the API exchanges are UTF-8 byte offsets into the subject text, matching the
 * conventions of the underlying `oniguruma-jni`/`oniguruma-ffm` bindings.
 *
 * Caller contract: every [OnigurumaRegex] and [OnigurumaString] obtained from this instance must
 * be closed exactly once before [close]. The library deliberately does not track closed state or
 * enforce lifecycle ordering: using a regex or string after it was closed, closing it twice,
 * closing it concurrently with [match], or closing this instance while handles are still live is
 * caller error and, on backends managing native memory, may fail with undefined behavior.
 */
public interface Oniguruma : AutoCloseable {
    /**
     * Compiles [pattern] into a regex handle.
     *
     * [pattern] must be valid UTF-8: the bytes are handed to Oniguruma without validation, and
     * matching against a regex compiled from malformed input is undefined.
     *
     * @throws OnigurumaException if the pattern does not compile; the message carries the
     * Oniguruma diagnostic
     */
    public fun createRegex(pattern: ByteArray): OnigurumaRegex

    /**
     * Prepares [utf8Content] as a subject text for [match].
     *
     * [utf8Content] must be valid UTF-8: the bytes are handed to Oniguruma without validation,
     * and matching against malformed input is undefined.
     */
    public fun createString(utf8Content: ByteArray): OnigurumaString

    /**
     * Searches [text] for the first match of [regex] at or after [byteOffset].
     *
     * @param regex a regex created by this instance
     * @param text a string created by this instance
     * @param byteOffset position the search starts from, in UTF-8 bytes; must be in
     * `[0, text length]`
     * @param matchBeginPosition whether `\G` matches at the search start position
     * @param matchBeginString whether `\A` matches at the start of the text
     * @return `null` if the regex does not match, otherwise an array of `2 * regionCount`
     * UTF-8 byte offsets laid out as `[start0, end0, start1, end1, ...]`, where region 0 is the
     * whole match and subsequent regions are capture groups; groups that did not participate in
     * the match report `-1, -1`
     * @throws IllegalArgumentException if [byteOffset] is out of range or [regex]/[text] were
     * created by a different [Oniguruma] instance
     */
    public fun match(
        regex: OnigurumaRegex,
        text: OnigurumaString,
        byteOffset: Int,
        matchBeginPosition: Boolean,
        matchBeginString: Boolean,
    ): IntArray?
}

/**
 * A compiled Oniguruma regular expression.
 *
 * This is a thin resource wrapper. It does not track whether it has been closed. Clients must
 * close each instance exactly once, must not use it after closing, and must close it before
 * closing the owning [Oniguruma] instance.
 */
public interface OnigurumaRegex : AutoCloseable

/**
 * A subject text prepared for Oniguruma matching.
 *
 * This is a thin resource wrapper. It does not track whether it has been closed. Clients must
 * close each instance exactly once, must not use it after closing, and must close it before
 * closing the owning [Oniguruma] instance.
 */
public interface OnigurumaString : AutoCloseable

/** Reports an error from the underlying Oniguruma backend. */
public class OnigurumaException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Creates an [Oniguruma] instance backed by the platform's default backend.
 *
 * The function suspends because some backends (WebAssembly) load asynchronously; on platforms
 * with synchronous loading it returns immediately.
 */
public expect suspend fun createOniguruma(): Oniguruma
