package me.zolotov.oniguruma

import java.nio.file.Path
import me.zolotov.oniguruma.ffm.Oniguruma as FfmOniguruma
import me.zolotov.oniguruma.ffm.OnigurumaException as FfmOnigurumaException
import me.zolotov.oniguruma.ffm.OnigurumaRegex as FfmOnigurumaRegex
import me.zolotov.oniguruma.ffm.OnigurumaString as FfmOnigurumaString

/**
 * Creates an [Oniguruma] backed by `oniguruma-ffm` with the bundled native library.
 *
 * Native access must be enabled for the FFM downcalls: run with
 * `--enable-native-access=ALL-UNNAMED` (classpath) or
 * `--enable-native-access=me.zolotov.oniguruma.ffm` (module path).
 */
public actual suspend fun createOniguruma(): Oniguruma = createOniguruma(libraryPath = null)

/**
 * Creates an [Oniguruma] backed by `oniguruma-ffm`, loading `libonig` from [libraryPath], or the
 * bundled/system library when [libraryPath] is `null`.
 */
public fun createOniguruma(libraryPath: Path?): Oniguruma {
    val ffm = translatingFfmErrors {
        if (libraryPath != null) FfmOniguruma.createFromFile(libraryPath) else FfmOniguruma.createFromResources()
    }
    return FfmBackedOniguruma(ffm)
}

private class FfmBackedOniguruma(private val ffm: FfmOniguruma) : Oniguruma {
    override fun createRegex(pattern: ByteArray): OnigurumaRegex =
        FfmBackedRegex(this, translatingFfmErrors { ffm.createRegex(pattern) })

    override fun createString(utf8Content: ByteArray): OnigurumaString =
        FfmBackedString(this, translatingFfmErrors { ffm.createString(utf8Content) })

    override fun match(
        regex: OnigurumaRegex,
        text: OnigurumaString,
        byteOffset: Int,
        matchBeginPosition: Boolean,
        matchBeginString: Boolean,
    ): IntArray? {
        require(regex is FfmBackedRegex && regex.owner === this) {
            "regex was created by a different Oniguruma instance"
        }
        require(text is FfmBackedString && text.owner === this) {
            "text was created by a different Oniguruma instance"
        }
        return translatingFfmErrors {
            ffm.match(regex.delegate, text.delegate, byteOffset, matchBeginPosition, matchBeginString)
        }
    }

    override fun close() {
        translatingFfmErrors { ffm.close() }
    }
}

private class FfmBackedRegex(val owner: FfmBackedOniguruma, val delegate: FfmOnigurumaRegex) : OnigurumaRegex {
    override fun close() {
        translatingFfmErrors { delegate.close() }
    }
}

private class FfmBackedString(val owner: FfmBackedOniguruma, val delegate: FfmOnigurumaString) : OnigurumaString {
    override fun close() {
        translatingFfmErrors { delegate.close() }
    }
}

// Rethrown as the common exception type so that a caller catching OnigurumaException from the
// common API observes the same behavior on every platform.
private inline fun <T> translatingFfmErrors(block: () -> T): T {
    try {
        return block()
    } catch (e: FfmOnigurumaException) {
        throw OnigurumaException(e.message ?: "Oniguruma error", e)
    }
}
