# oniguruma-ffm

[![Maven central version](https://img.shields.io/maven-central/v/me.zolotov.oniguruma/oniguruma-ffm.svg)](https://search.maven.org/artifact/me.zolotov.oniguruma/oniguruma-ffm)

A Java Foreign Function & Memory (FFM) wrapper for the Oniguruma regular expression library.

## Installation

The library is published in two flavors:

- `full` (default): bundles `libonig` for all supported platforms and loads via `Oniguruma.createFromResources()`
- `slim`: publishes the Java classes without bundled native libraries

### Full jar

```kotlin
dependencies {
    implementation("me.zolotov.oniguruma:oniguruma-ffm:$version")
}
```

### Slim jar

```kotlin
val onigurumaPackaging = Attribute.of("me.zolotov.oniguruma.packaging", String::class.java)

dependencies {
    implementation("me.zolotov.oniguruma:oniguruma-ffm:$version") {
        attributes {
            attribute(onigurumaPackaging, "slim")
        }
    }
}
```

With the slim jar, load the library from an explicit file path via `Oniguruma.createFromFile(path)`, or let `createFromResources()` fall back to system locations.
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
    onigurumaNativeBinding("me.zolotov.oniguruma:oniguruma-ffm:$version")
}
```

## Runtime requirements

Enable native access when running the bindings:

- Classpath applications: `--enable-native-access=ALL-UNNAMED`
- Module-path applications: `--enable-native-access=me.zolotov.oniguruma.ffm`

## Usage

### Basic setup

```java
import me.zolotov.oniguruma.ffm.Oniguruma;

import java.nio.file.Path;

var bundled = Oniguruma.createFromResources();
var fromFile = Oniguruma.createFromFile(Path.of("/path/to/libonig.dylib"));
```

### Pattern matching

```java
import me.zolotov.oniguruma.ffm.Oniguruma;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

var pattern = "to".getBytes(StandardCharsets.UTF_8);
var text = "text to match".getBytes(StandardCharsets.UTF_8);

try (var oniguruma = Oniguruma.createFromResources()) {
    try (var regex = oniguruma.createRegex(pattern)) {
        try (var string = oniguruma.createString(text)) {
            var result = oniguruma.match(
                regex,
                string,
                0, 
                true, 
                false);
            System.out.println(Arrays.toString(result));
        }
    }
}
```

## Building

Prerequisites:

1. JDK 25 or later
2. CMake
3. A native C compiler for the current platform

Run tests:

```bash
./gradlew :oniguruma-ffm:test
```

Build the module:

```bash
./gradlew :oniguruma-ffm:build
```

Run benchmarks:

```bash
./gradlew :oniguruma-ffm:jmh
```

This library is primarily intended for use with the `textmate-core` library in IntelliJ-based IDEs, though it can also be used independently.