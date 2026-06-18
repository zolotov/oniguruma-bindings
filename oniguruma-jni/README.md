# oniguruma-jni

[![Maven central version](https://img.shields.io/maven-central/v/me.zolotov.oniguruma/oniguruma-jni.svg)](https://search.maven.org/artifact/me.zolotov.oniguruma/oniguruma-jni)

A Java Native Interface (JNI) wrapper for the Oniguruma regular expression library, implemented in Rust using the [`onig`](https://crates.io/crates/onig) crate.

## Installation

The library is published in two flavors:

- `full` (default): bundles native libraries for all supported platforms and loads via `Oniguruma.createFromResources()`
- `slim`: publishes the Java classes without bundled native libraries

### Full jar

```kotlin
dependencies {
    implementation("me.zolotov.oniguruma:oniguruma-jni:$version")
}
```

### Slim jar

```kotlin
val onigurumaPackaging = Attribute.of("me.zolotov.oniguruma.packaging", String::class.java)

dependencies {
    implementation("me.zolotov.oniguruma:oniguruma-jni:$version") {
        attributes {
            attribute(onigurumaPackaging, "slim")
        }
    }
}
```

With the slim jar, load the library from an explicit file path via `Oniguruma.createFromFile(path)`.
Per-platform native artifacts are also published and can be resolved with the `me.zolotov.oniguruma.platform` attribute (`<os>-<arch>`, for example `macos-aarch64`, `linux-x86_64`, or `windows-x86_64`):

```kotlin
val onigurumaPlatform = Attribute.of("me.zolotov.oniguruma.platform", String::class.java)
val onigurumaNativeBinding: Configuration by configurations.creating {
    isCanBeConsumed = false
    isTransitive = false
    attributes {
        attribute(onigurumaPlatform, "macos-aarch64")
    }
}

dependencies {
    onigurumaNativeBinding("me.zolotov.oniguruma:oniguruma-jni:$version")
}
```

## Runtime requirements

On Java 24 and newer, enable native access for the binding:

- Classpath applications: `--enable-native-access=ALL-UNNAMED`
- Module-path applications: `--enable-native-access=me.zolotov.oniguruma.jni`

## Usage

### Basic setup

```java
import me.zolotov.oniguruma.jni.Oniguruma;

import java.nio.file.Path;

var bundled = Oniguruma.createFromResources();
var fromFile = Oniguruma.createFromFile(Path.of("/path/to/library"));
```

Loading from resources is convenient, but it unpacks the native library to disk.
If you already manage the native library yourself, `createFromFile(...)` avoids that overhead.

### Pattern matching

```java
import me.zolotov.oniguruma.jni.Oniguruma;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

var oniguruma = Oniguruma.createFromResources();
var pattern = "to".getBytes(StandardCharsets.UTF_8);
var text = "text to match".getBytes(StandardCharsets.UTF_8);

long textPtr = oniguruma.createString(text);
try {
    long regexPtr = oniguruma.createRegex(pattern);
    try {
        int[] result = oniguruma.match(regexPtr, textPtr, 0, true, false);
        System.out.println(Arrays.toString(result));
    } finally {
        oniguruma.freeRegex(regexPtr);
    }
} finally {
    oniguruma.freeString(textPtr);
}
```

## Building

Prerequisites:

1. JDK 17 or later
2. Rust toolchain

Run tests:

```bash
./gradlew :oniguruma-jni:test
```

Build the module:

```bash
./gradlew :oniguruma-jni:build
```

Run benchmarks:

```bash
./gradlew :oniguruma-jni:jmh
```

This library is primarily intended for use with the `textmate-core` library in IntelliJ-based IDEs, though it can also be used independently.
