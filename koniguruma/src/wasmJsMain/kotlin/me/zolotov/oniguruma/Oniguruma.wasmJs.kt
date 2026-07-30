package me.zolotov.oniguruma

import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.js.Promise

// OnigRegion reports groups that did not participate in a match as -1; the wasm binding hands
// offsets to JavaScript through a HEAPU32 view, so -1 arrives as an unsigned 2^32 - 1.
private const val UNMATCHED_GROUP_SENTINEL = 4294967295.0

/**
 * Creates an [Oniguruma] backed by `onig.wasm` via the `vscode-oniguruma` npm package.
 *
 * The WebAssembly module must be loaded once per process. Under Node.js this function loads
 * `vscode-oniguruma/release/onig.wasm` from `node_modules` automatically; in browsers, where the
 * application controls how assets are served, load the binary explicitly with the
 * `createOniguruma(wasmBinary)` overload first.
 */
public actual suspend fun createOniguruma(): Oniguruma = createOniguruma(wasmBinary = null)

/**
 * Creates an [Oniguruma] backed by `onig.wasm`, loading the WebAssembly module from [wasmBinary]:
 * an `ArrayBuffer`, an `ArrayBufferView`, or a `fetch` `Response` for the `onig.wasm` asset.
 * The binary is loaded once per process; subsequent calls reuse the already-loaded module.
 */
public suspend fun createOniguruma(wasmBinary: JsAny?): Oniguruma = WasmOniguruma(loadBinding(wasmBinary))

// The vscode-oniguruma module namespace, once the wasm has been loaded through it. The module is
// imported dynamically because it ships as UMD/CommonJS: Node's ES module interop does not expose
// its named exports, so a static @JsModule import binds them to undefined.
private var loadedBinding: JsAny? = null

private suspend fun loadBinding(wasmBinary: JsAny?): JsAny {
    loadedBinding?.let { return it }
    val binding = awaitPromise(importVscodeOniguruma())
        ?: throw OnigurumaException("Failed to import the vscode-oniguruma module")
    val data = wasmBinary
        ?: readOnigWasmFromNodeModules()
        ?: throw OnigurumaException(
            "onig.wasm is not available: on this platform, pass the binary to createOniguruma(wasmBinary)"
        )
    awaitPromise(loadWasm(binding, data))
    loadedBinding = binding
    return binding
}

private class WasmOniguruma(private val binding: JsAny) : Oniguruma {
    override fun createRegex(pattern: ByteArray): OnigurumaRegex {
        val scanner = try {
            createScanner(binding, pattern.decodeToString())
        } catch (e: Throwable) {
            throw OnigurumaException("Failed to compile pattern: ${jsErrorMessage(e)}", e)
        }
        return WasmRegex(this, scanner)
    }

    override fun createString(utf8Content: ByteArray): OnigurumaString = WasmString(this, binding, utf8Content)

    override fun match(
        regex: OnigurumaRegex,
        text: OnigurumaString,
        byteOffset: Int,
        matchBeginPosition: Boolean,
        matchBeginString: Boolean,
    ): IntArray? {
        require(regex is WasmRegex && regex.owner === this) {
            "regex was created by a different Oniguruma instance"
        }
        require(text is WasmString && text.owner === this) {
            "text was created by a different Oniguruma instance"
        }
        val textLength = text.utf8Length
        if (byteOffset < 0 || byteOffset > textLength) {
            throw IllegalArgumentException("byteOffset $byteOffset out of range [0, $textLength]")
        }

        val match = findNextMatchSync(
            regex.scanner,
            text.onigString,
            text.toUtf16Offset(byteOffset),
            notBeginPosition = !matchBeginPosition,
            notBeginString = !matchBeginString,
        ) ?: return null

        val regionCount = captureCount(match)
        val offsets = IntArray(regionCount * 2)
        for (i in 0 until regionCount) {
            val start = text.toUtf8Offset(captureStart(match, i))
            offsets[2 * i] = start
            offsets[2 * i + 1] = if (start == -1) -1 else text.toUtf8Offset(captureEnd(match, i))
        }
        return offsets
    }

    override fun close() {
        // The wasm module is process-global and regexes/strings are freed individually;
        // there is no per-instance state to release.
    }
}

private class WasmRegex(val owner: WasmOniguruma, val scanner: JsAny) : OnigurumaRegex {
    override fun close() {
        dispose(scanner)
    }
}

private class WasmString(val owner: WasmOniguruma, binding: JsAny, utf8Content: ByteArray) : OnigurumaString {
    val utf8Length: Int = utf8Content.size
    val onigString: JsAny

    // Offset maps between the UTF-8 bytes the common API speaks and the UTF-16 offsets
    // vscode-oniguruma speaks; both null when the text is pure ASCII and offsets coincide.
    private val utf8ToUtf16: IntArray?
    private val utf16ToUtf8: IntArray?

    init {
        val content = utf8Content.decodeToString()
        val utf16Length = content.length
        if (utf16Length == utf8Length) {
            utf8ToUtf16 = null
            utf16ToUtf8 = null
        } else {
            val toUtf16 = IntArray(utf8Length + 1)
            val toUtf8 = IntArray(utf16Length + 1)
            var byteIndex = 0
            var charIndex = 0
            while (charIndex < utf16Length) {
                val current = content[charIndex]
                val isPair = current.isHighSurrogate() &&
                    charIndex + 1 < utf16Length &&
                    content[charIndex + 1].isLowSurrogate()
                val codePoint = if (isPair) {
                    (current.code - 0xD800) * 0x400 + (content[charIndex + 1].code - 0xDC00) + 0x10000
                } else {
                    current.code
                }
                val byteCount = when {
                    codePoint <= 0x7F -> 1
                    codePoint <= 0x7FF -> 2
                    codePoint <= 0xFFFF -> 3
                    else -> 4
                }
                toUtf8[charIndex] = byteIndex
                if (isPair) {
                    toUtf8[charIndex + 1] = byteIndex
                }
                for (b in 0 until byteCount) {
                    toUtf16[byteIndex + b] = charIndex
                }
                byteIndex += byteCount
                charIndex += if (isPair) 2 else 1
            }
            toUtf8[utf16Length] = utf8Length
            toUtf16[utf8Length] = utf16Length
            utf8ToUtf16 = toUtf16
            utf16ToUtf8 = toUtf8
        }
        onigString = createOnigString(binding, content)
    }

    fun toUtf16Offset(byteOffset: Int): Int = utf8ToUtf16?.get(byteOffset) ?: byteOffset

    /**
     * Converts a capture offset back to UTF-8 bytes. For pure-ASCII texts an unmatched group's
     * `-1` arrives here unconverted as 2^32 - 1 and is restored to `-1`. For texts with offset
     * maps, vscode-oniguruma clamps unmatched groups to the end of the text before this code can
     * see them — the same behavior vscode-textmate runs on.
     */
    fun toUtf8Offset(utf16Offset: Double): Int {
        if (utf16Offset == UNMATCHED_GROUP_SENTINEL) {
            return -1
        }
        val offset = utf16Offset.toInt()
        return utf16ToUtf8?.get(offset) ?: offset
    }

    override fun close() {
        dispose(onigString)
    }
}

private suspend fun awaitPromise(promise: Promise<JsAny?>): JsAny? = suspendCoroutine { continuation ->
    promise.then(
        { value ->
            continuation.resume(value)
            null
        },
        { error ->
            continuation.resumeWithException(
                OnigurumaException("Failed to load onig.wasm: ${stringifyJsValue(error)}")
            )
            null
        }
    )
}

private fun jsErrorMessage(e: Throwable): String {
    val thrownValue = (e as? JsException)?.thrownValue
    return if (thrownValue != null) stringifyJsValue(thrownValue) else e.message ?: "unknown error"
}

// `m.default` is the CommonJS `module.exports` Node's ES module interop exposes; a real ES module
// build would carry the named exports on the namespace itself, hence the fallback to `m`.
private fun importVscodeOniguruma(): Promise<JsAny?> =
    js("import('vscode-oniguruma').then((m) => m.default || m)")

/**
 * Reads `onig.wasm` out of the `vscode-oniguruma` npm package when running under Node.js;
 * returns `null` elsewhere (browsers serve the binary themselves).
 */
private fun readOnigWasmFromNodeModules(): JsAny? = js(
    """{
    if (typeof process === 'undefined' || typeof process.getBuiltinModule !== 'function') return null;
    try {
        var nodeModule = process.getBuiltinModule('module');
        var fs = process.getBuiltinModule('fs');
        var require = nodeModule.createRequire(import.meta.url);
        return fs.readFileSync(require.resolve('vscode-oniguruma/release/onig.wasm'));
    } catch (e) {
        return null;
    }
    }"""
)

private fun loadWasm(binding: JsAny, data: JsAny): Promise<JsAny?> = js("binding.loadWASM(data)")

private fun createScanner(binding: JsAny, pattern: String): JsAny = js("new binding.OnigScanner([pattern])")

private fun createOnigString(binding: JsAny, content: String): JsAny = js("new binding.OnigString(content)")

private fun dispose(handle: JsAny): Unit = js("{ handle.dispose(); }")

/**
 * [startPosition] is a UTF-16 offset into the string; the returned match's capture offsets are
 * UTF-16 as well — vscode-oniguruma converts from and to Oniguruma's UTF-8 offsets internally.
 * The find options are OR-ed with the scanner's compile options, which default to
 * `ONIG_OPTION_CAPTURE_GROUP`, same as the JVM backends. 23 and 21 are the `FindOption` const
 * enum ordinals for `NotBeginPosition` and `NotBeginString`.
 */
private fun findNextMatchSync(
    scanner: JsAny,
    string: JsAny,
    startPosition: Int,
    notBeginPosition: Boolean,
    notBeginString: Boolean,
): JsAny? = js(
    """{
    var options = [];
    if (notBeginPosition) options.push(23);
    if (notBeginString) options.push(21);
    return scanner.findNextMatchSync(string, startPosition, options);
    }"""
)

private fun captureCount(match: JsAny): Int = js("match.captureIndices.length")

private fun captureStart(match: JsAny, index: Int): Double = js("match.captureIndices[index].start")

private fun captureEnd(match: JsAny, index: Int): Double = js("match.captureIndices[index].end")

private fun stringifyJsValue(value: JsAny?): String = js("String(value && value.message || value)")
