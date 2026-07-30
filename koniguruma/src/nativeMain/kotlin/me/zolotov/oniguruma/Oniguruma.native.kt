package me.zolotov.oniguruma

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.plus
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import oniguruma.ONIG_MAX_ERROR_MESSAGE_LEN
import oniguruma.ONIG_MISMATCH
import oniguruma.ONIG_OPTION_CAPTURE_GROUP
import oniguruma.ONIG_OPTION_NONE
import oniguruma.ONIG_OPTION_NOT_BEGIN_POSITION
import oniguruma.ONIG_OPTION_NOT_BEGIN_STRING
import oniguruma.OnigDefaultSyntax
import oniguruma.OnigEncodingUTF8
import oniguruma.OnigErrorInfo
import oniguruma.onig_error_code_to_str
import oniguruma.onig_free
import oniguruma.onig_initialize
import oniguruma.onig_new
import oniguruma.onig_region_free
import oniguruma.onig_region_new
import oniguruma.onig_search
import oniguruma.regex_t
import platform.posix.memcpy

/**
 * Creates an [Oniguruma] backed by the Oniguruma C library compiled into this binary by the
 * cinterop klib; nothing is loaded at runtime, so the factory returns immediately.
 */
@OptIn(ExperimentalForeignApi::class)
public actual suspend fun createOniguruma(): Oniguruma {
    onigurumaInitialized
    return NativeOniguruma()
}

// onig_initialize must run once per process before the first onig_new. Mirroring the JVM
// backends, onig_end is deliberately never called: oniguruma's globals live until process exit,
// which is cheaper than coordinating a safe teardown point across all Oniguruma instances.
@OptIn(ExperimentalForeignApi::class)
private val onigurumaInitialized: Unit by lazy {
    memScoped {
        val encodings = allocArrayOf(OnigEncodingUTF8.ptr)
        val rc = onig_initialize(encodings, 1)
        if (rc != 0) {
            throw OnigurumaException("onig_initialize failed with code $rc")
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private class NativeOniguruma : Oniguruma {
    override fun createRegex(pattern: ByteArray): OnigurumaRegex = memScoped {
        val regexOut = alloc<CPointerVar<regex_t>>()
        // onig_compile clears errorInfo itself, but an error raised before it runs leaves the
        // struct untouched, and onig_error_code_to_str would then dereference garbage; alloc
        // returns zeroed memory, so no explicit clearing is needed here.
        val errorInfo = alloc<OnigErrorInfo>()

        val patternBuffer = allocArray<UByteVar>(maxOf(pattern.size, 1))
        if (pattern.isNotEmpty()) {
            pattern.usePinned { pinned ->
                memcpy(patternBuffer, pinned.addressOf(0), pattern.size.convert())
            }
        }

        val rc = onig_new(
            regexOut.ptr,
            patternBuffer,
            (patternBuffer + pattern.size)!!,
            ONIG_OPTION_CAPTURE_GROUP,
            OnigEncodingUTF8.ptr,
            OnigDefaultSyntax,
            errorInfo.ptr,
        )
        if (rc != 0) {
            throw OnigurumaException("Failed to compile pattern: ${errorMessage(rc, errorInfo.ptr)}")
        }
        NativeRegex(this@NativeOniguruma, regexOut.value!!)
    }

    override fun createString(utf8Content: ByteArray): OnigurumaString {
        val buffer = nativeHeap.allocArray<UByteVar>(maxOf(utf8Content.size, 1))
        if (utf8Content.isNotEmpty()) {
            utf8Content.usePinned { pinned ->
                memcpy(buffer, pinned.addressOf(0), utf8Content.size.convert())
            }
        }
        return NativeString(this, buffer, utf8Content.size)
    }

    override fun match(
        regex: OnigurumaRegex,
        text: OnigurumaString,
        byteOffset: Int,
        matchBeginPosition: Boolean,
        matchBeginString: Boolean,
    ): IntArray? {
        require(regex is NativeRegex && regex.owner === this) {
            "regex was created by a different Oniguruma instance"
        }
        require(text is NativeString && text.owner === this) {
            "text was created by a different Oniguruma instance"
        }
        val textLength = text.contentLength
        if (byteOffset < 0 || byteOffset > textLength) {
            throw IllegalArgumentException("byteOffset $byteOffset out of range [0, $textLength]")
        }

        var options = ONIG_OPTION_NONE
        if (!matchBeginPosition) {
            options = options or ONIG_OPTION_NOT_BEGIN_POSITION
        }
        if (!matchBeginString) {
            options = options or ONIG_OPTION_NOT_BEGIN_STRING
        }

        // A fresh region per search keeps match() thread-safe without the per-thread region
        // pooling the JVM backends do; revisit if the three malloc/free pairs ever show up in
        // profiles.
        val region = onig_region_new() ?: throw OnigurumaException("onig_region_new returned null")
        try {
            val textStart = text.buffer
            val textEnd = (textStart + textLength)!!
            val rc = onig_search(
                regex.handle,
                textStart,
                textEnd,
                (textStart + byteOffset)!!,
                textEnd,
                region,
                options,
            )
            if (rc == ONIG_MISMATCH) {
                return null
            }
            if (rc < 0) {
                throw OnigurumaException("onig_search failed: ${errorMessage(rc, null)}")
            }

            val numRegs = region.pointed.num_regs
            if (numRegs <= 0) {
                return IntArray(0)
            }
            val beg = region.pointed.beg!!
            val end = region.pointed.end!!
            val offsets = IntArray(numRegs * 2)
            for (i in 0 until numRegs) {
                offsets[2 * i] = beg[i]
                offsets[2 * i + 1] = end[i]
            }
            return offsets
        } finally {
            onig_region_free(region, 1)
        }
    }

    override fun close() {
        // Regexes and strings are freed individually and the library's globals are process-wide;
        // there is no per-instance state to release.
    }

    private fun errorMessage(code: Int, errorInfo: CPointer<OnigErrorInfo>?): String = memScoped {
        val buffer = allocArray<UByteVar>(ONIG_MAX_ERROR_MESSAGE_LEN)
        val length = if (errorInfo != null) {
            onig_error_code_to_str(buffer, code, errorInfo)
        } else {
            onig_error_code_to_str(buffer, code)
        }
        buffer.readBytes(length).decodeToString()
    }
}

@OptIn(ExperimentalForeignApi::class)
private class NativeRegex(val owner: NativeOniguruma, val handle: CPointer<regex_t>) : OnigurumaRegex {
    override fun close() {
        onig_free(handle)
    }
}

@OptIn(ExperimentalForeignApi::class)
private class NativeString(
    val owner: NativeOniguruma,
    val buffer: CPointer<UByteVar>,
    val contentLength: Int,
) : OnigurumaString {
    override fun close() {
        nativeHeap.free(buffer.rawValue)
    }
}
