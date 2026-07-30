# Oniguruma Bindings

[![oniguruma-jni on Maven Central](https://img.shields.io/maven-central/v/me.zolotov.oniguruma/oniguruma-jni?label=oniguruma-jni)](https://search.maven.org/artifact/me.zolotov.oniguruma/oniguruma-jni)
[![oniguruma-ffm on Maven Central](https://img.shields.io/maven-central/v/me.zolotov.oniguruma/oniguruma-ffm?label=oniguruma-ffm)](https://search.maven.org/artifact/me.zolotov.oniguruma/oniguruma-ffm)
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

## Performance

Both modules run the same JMH suite against shared inputs; the results are published
continuously to the [benchmark dashboard](https://zolotov.github.io/oniguruma-bindings/).
The FFM binding is consistently ahead on operations dominated by per-call binding overhead.
Where the time comes from:

- **Cheaper native calls.** FFM downcall handles compile to specialized stubs that are close
  to a plain native call. A JNI native method pays for argument marshalling and the JNI
  transition on every crossing, and the Rust side adds a `catch_unwind` wrapper per call.
- **Results are read, not marshalled.** After a match, FFM reads the offsets straight out of
  the native `OnigRegion` struct and writes one Java `int[]`. The JNI binding has to build the
  array through JNI functions (`NewIntArray` + `SetIntArrayRegion`), each itself a JNI crossing.
- **One copy per string, no pinning.** `createString` in FFM is a `malloc` and one copy into
  native memory. The JNI path enters a GC-critical section to pin the Java array, copies into
  the Rust heap, and boxes the result.
- **Cheaper error paths.** A failed FFM compile builds one Java exception with the oniguruma
  diagnostic. The JNI path constructs a Rust error, formats it, and re-throws it across the
  boundary through the JNI exception machinery.
- **Per-call allocations are reused.** Both bindings reuse a per-thread match region instead of
  calling `onig_region_new`/`onig_region_free` around every search; FFM also compiles patterns
  through a reused per-thread scratch block, so a small `createRegex` performs no allocation
  besides the returned handle wrapper.

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

- [Oniguruma](https://github.com/kkos/oniguruma) – the regular expression library both backends build
  on, written and maintained by [K.Kosako](https://github.com/kkos).
- [rust-onig](https://github.com/rust-onig/rust-onig) – the [`onig`](https://crates.io/crates/onig)
  crate the JNI backend is implemented with, maintained by
  [Will Speak](https://github.com/iwillspeak), [Ivan Ivashchenko](https://github.com/defuz) and
  [Vincent Prouillet](https://github.com/Keats).

## Note

This library is primarily intended for use with the `textmate-core` library in IntelliJ-based IDEs. While it can be used independently, the API is designed with this specific use case in mind.
