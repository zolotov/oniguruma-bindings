# koniguruma

A Kotlin Multiplatform wrapper for the Oniguruma regular expression library, exposing one common
API over the platform-specific [oniguruma-bindings](https://github.com/zolotov/oniguruma-bindings)
backends.

## Supported targets

| Target | Backend |
|--------|---------|
| JVM    | [`oniguruma-ffm`](../oniguruma-ffm/README.md), Java Foreign Function & Memory over the upstream C library |

## Installation

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("me.zolotov.oniguruma:koniguruma:0.1.0")
        }
    }
}
```

## Usage

The API mirrors the underlying bindings: patterns and subject texts are UTF-8 byte arrays, and all
offsets the API exchanges are UTF-8 byte offsets.

```kotlin
val oniguruma = createOniguruma() // suspends: some backends load asynchronously

oniguruma.use {
    val regex = it.createRegex("([0-9]+):([0-9]+)".encodeToByteArray())
    val text = it.createString("12:00pm".encodeToByteArray())
    try {
        // [start0, end0, start1, end1, ...] byte offsets, region 0 is the whole match;
        // null when the regex does not match, -1/-1 for groups that did not participate
        val offsets: IntArray? = it.match(
            regex,
            text,
            byteOffset = 0,
            matchBeginPosition = true,
            matchBeginString = true,
        )
    } finally {
        text.close()
        regex.close()
    }
}
```

Every `OnigurumaRegex` and `OnigurumaString` must be closed exactly once before the owning
`Oniguruma` instance is closed; the wrapper is as thin as the bindings underneath and does not
track handle lifecycle.

## Runtime requirements

### JVM

The FFM backend requires Java 25 and enabled native access:

- Classpath applications: `--enable-native-access=ALL-UNNAMED`
- Module-path applications: `--enable-native-access=me.zolotov.oniguruma.ffm`

`createOniguruma()` loads the `libonig` bundled with `oniguruma-ffm`; the JVM-specific
`createOniguruma(libraryPath)` overload loads it from an explicit file path instead.
