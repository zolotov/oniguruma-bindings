package me.zolotov.oniguruma

import android.content.Context
import java.io.File
import me.zolotov.oniguruma.jni.Oniguruma as JniOniguruma

/**
 * Creates an [Oniguruma] backed by `oniguruma-jni`, locating the application [Context] through
 * the platform runtime. Prefer the `createOniguruma(context)` overload when a [Context] is at
 * hand; this parameterless variant exists to satisfy the common API.
 */
public actual suspend fun createOniguruma(): Oniguruma {
    val context = currentApplicationContext()
        ?: throw OnigurumaException(
            "Unable to locate the application Context to find the packaged JNI library; " +
                "pass one explicitly via createOniguruma(context)"
        )
    return createOniguruma(context)
}

/**
 * Creates an [Oniguruma] backed by `oniguruma-jni`, loading `liboniguruma_jni.so` from the
 * application's native library directory.
 *
 * `oniguruma-jni` does not ship Android binaries: the application must bundle
 * `liboniguruma_jni.so` built for its supported ABIs (e.g. in `jniLibs`).
 */
public fun createOniguruma(context: Context): Oniguruma {
    val library = File(context.applicationInfo.nativeLibraryDir, "liboniguruma_jni.so")
    if (!library.isFile) {
        throw OnigurumaException(
            "liboniguruma_jni.so is not packaged for this device's ABI (looked at $library); " +
                "bundle oniguruma-jni's native library for your supported ABIs in the app's jniLibs"
        )
    }
    return JniBackedOniguruma(JniOniguruma.createFromFile(library.toPath()))
}

/**
 * `ActivityThread.currentApplication()` — the framework-provided way to reach the application
 * Context from static code. Returns null in processes where the framework is not initialized
 * (e.g. plain unit tests), where the caller must use `createOniguruma(context)` instead.
 */
private fun currentApplicationContext(): Context? = try {
    Class.forName("android.app.ActivityThread")
        .getMethod("currentApplication")
        .invoke(null) as? Context
} catch (_: ReflectiveOperationException) {
    null
}

private class JniBackedOniguruma(private val jni: JniOniguruma) : Oniguruma {
    override fun createRegex(pattern: ByteArray): OnigurumaRegex {
        val handle = try {
            jni.createRegex(pattern)
        } catch (e: RuntimeException) {
            throw OnigurumaException("Failed to compile pattern: ${e.message}", e)
        }
        return JniRegex(this, handle)
    }

    override fun createString(utf8Content: ByteArray): OnigurumaString =
        JniString(this, jni.createString(utf8Content), utf8Content.size)

    override fun match(
        regex: OnigurumaRegex,
        text: OnigurumaString,
        byteOffset: Int,
        matchBeginPosition: Boolean,
        matchBeginString: Boolean,
    ): IntArray? {
        require(regex is JniRegex && regex.owner === this) {
            "regex was created by a different Oniguruma instance"
        }
        require(text is JniString && text.owner === this) {
            "text was created by a different Oniguruma instance"
        }
        val textLength = text.contentLength
        if (byteOffset < 0 || byteOffset > textLength) {
            throw IllegalArgumentException("byteOffset $byteOffset out of range [0, $textLength]")
        }
        return jni.match(regex.handle, text.handle, byteOffset, matchBeginPosition, matchBeginString)
    }

    override fun close() {
        // Regexes and strings are freed individually and the JNI library stays loaded for the
        // process lifetime; there is no per-instance state to release.
    }

    fun freeRegexHandle(handle: Long) {
        jni.freeRegex(handle)
    }

    fun freeStringHandle(handle: Long) {
        jni.freeString(handle)
    }
}

private class JniRegex(val owner: JniBackedOniguruma, val handle: Long) : OnigurumaRegex {
    override fun close() {
        owner.freeRegexHandle(handle)
    }
}

private class JniString(
    val owner: JniBackedOniguruma,
    val handle: Long,
    val contentLength: Int,
) : OnigurumaString {
    override fun close() {
        owner.freeStringHandle(handle)
    }
}
