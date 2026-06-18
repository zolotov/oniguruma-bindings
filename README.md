# Oniguruma Bindings

[![JNI Maven Central version](https://img.shields.io/maven-central/v/me.zolotov.oniguruma/oniguruma-jni.svg)](https://search.maven.org/artifact/me.zolotov.oniguruma/oniguruma-jni)
[![FFM Maven Central version](https://img.shields.io/maven-central/v/me.zolotov.oniguruma/oniguruma-ffm.svg)](https://search.maven.org/artifact/me.zolotov.oniguruma/oniguruma-ffm)
[![GitHub Actions Workflow Status](https://img.shields.io/github/actions/workflow/status/zolotov/oniguruma-bindings/build.yaml)](https://github.com/zolotov/oniguruma-bindings/actions/workflows/build.yaml)
[![GitHub License](https://img.shields.io/github/license/zolotov/oniguruma-bindings)](https://github.com/zolotov/oniguruma-bindings/blob/main/LICENSE)

Java bindings for the Oniguruma regular expression library.
This repository publishes a JNI backend backed by the [onig](https://crates.io/crates/onig) Rust crate and an FFM backend backed by the upstream C library.
Both modules are primarily designed to support syntax highlighting in [IntelliJ](https://www.jetbrains.com/idea/)-based IDEs through the [`textmate-core`](https://github.com/JetBrains/intellij-community/tree/master/plugins/textmate/core) library.

## Modules

### `oniguruma-jni`

A JNI wrapper implemented in Rust using the [`onig`](https://crates.io/crates/onig) crate.

- Maven coordinate: `me.zolotov.oniguruma:oniguruma-jni`
- Java package: `me.zolotov.oniguruma.jni`
- JPMS module: `me.zolotov.oniguruma.jni`
- Documentation: [`oniguruma-jni/README.md`](./oniguruma-jni/README.md)

### `oniguruma-ffm`

A Java Foreign Function & Memory wrapper backed by the upstream C library.

- Maven coordinate: `me.zolotov.oniguruma:oniguruma-ffm`
- Java package: `me.zolotov.oniguruma.ffm`
- JPMS module: `me.zolotov.oniguruma.ffm`
- Documentation: [`oniguruma-ffm/README.md`](./oniguruma-ffm/README.md)

## Releases

Each module now has its own release cadence:

- `oniguruma-jni`: changelog in [`oniguruma-jni/CHANGELOG.md`](./oniguruma-jni/CHANGELOG.md), tags and GitHub releases as `oniguruma-jni-vX.Y.Z`
- `oniguruma-ffm`: changelog in [`oniguruma-ffm/CHANGELOG.md`](./oniguruma-ffm/CHANGELOG.md), tags and GitHub releases as `oniguruma-ffm-vX.Y.Z`

## Building

Build all modules:

```bash
./gradlew build
```

Build a single module:

```bash
./gradlew :oniguruma-jni:build
./gradlew :oniguruma-ffm:build
```

Run module benchmarks:

```bash
./gradlew :oniguruma-jni:jmh
./gradlew :oniguruma-ffm:jmh
```

The repository also contains an internal `benchmarks` project with shared JMH inputs and state.

## Contributing

Contributions are welcome! Please feel free to submit pull requests.

## Acknowledgments

- Oniguruma library developers
- onig-rs crate maintainers

## Note

This library is primarily intended for use with the `textmate-core` library in IntelliJ-based IDEs. While it can be used independently, the API is designed with this specific use case in mind.
