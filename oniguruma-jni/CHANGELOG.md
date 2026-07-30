# Change Log

## [Unreleased]

### Changed

- `Oniguruma.createFromFile` after the library was already loaded from a different source (the
  bundled resources, or another path) now throws an `IllegalStateException` instead of silently
  returning a binding backed by the previously loaded library. Repeating the same source stays
  a no-op.
- `freeRegex` and `freeString` treat the `0` handle as a no-op instead of raising a
  `RuntimeException`. `createRegex` and `createString` return `0` for a null input, so the
  natural create/free pairing no longer throws on it.
- `match` now rejects a `byteOffset` outside `[0, length]` with an `IllegalArgumentException`,
  matching the `oniguruma-ffm` binding. Previously a negative offset silently reported no match
  and a too-large offset raised a generic `RuntimeException` from the native layer.
- `createRegex` and `createString` no longer validate that their input is UTF-8; malformed bytes
  now reach oniguruma instead of raising a `RuntimeException`. Valid UTF-8 was always the
  documented expectation, and the `oniguruma-ffm` binding never validated. Callers that relied on
  the error must validate before calling.

### Performance

- `createString` skips the UTF-8 validation pass, leaving a single copy into native memory.
- Match offsets are collected into a reused per-thread buffer instead of a freshly allocated
  vector on every match.

## [2.0.0] - 2026-06-18

Reimplemented in Java and renamed. Every consumer has to touch imports to upgrade.

### Breaking

- The Java package moved from `me.zolotov.oniguruma` to `me.zolotov.oniguruma.jni`. Update imports:
  `me.zolotov.oniguruma.Oniguruma` is now `me.zolotov.oniguruma.jni.Oniguruma`.
- The JPMS module was renamed from `me.zolotov.oniguruma` to `me.zolotov.oniguruma.jni`. Update
  `requires` directives, and `--enable-native-access=me.zolotov.oniguruma.jni` on Java 24 and newer.
- The binding is now written in Java instead of Kotlin and no longer requires kotlin-stdlib at
  runtime. `requires kotlin.stdlib` is gone from the module descriptor and kotlin-stdlib is gone
  from the POM, so declare it explicitly if you were relying on it arriving transitively.

### Changed

- The project moved to the `zolotov/oniguruma-bindings` repository, which now also publishes
  `oniguruma-ffm`. Releases are tagged per module as `oniguruma-jni-X.Y.Z`; releases up to this one
  are tagged without the prefix.

## [1.0.3] - 2026-06-11

Allocation and lookup overhead removed from the hot matching path. No API changes.

### Performance

- `match` reuses one Oniguruma `Region` per thread instead of allocating a fresh one on every
  call, removing a malloc/free pair from each match.
- `createString` reads the input array through JNI critical access, avoiding a copy of the pattern
  or subject bytes on each call.
- The failure path caches the `RuntimeException` class rather than looking it up by name every time
  an error is propagated out of native code.

### Fixed

- Fixed publication of the `slim` variant and the per-platform native artifacts: the module
  metadata advertised platform variants that the release build had not produced, so resolving them
  through the `me.zolotov.oniguruma.platform` attribute failed.

### Changed

- Built with Kotlin 2.3.10, up from 2.2.0. This changes the kotlin-stdlib version resolved
  transitively; `2.0.0` removes that dependency altogether.

## [1.0.2] - 2025-07-29

### Fixed

- The bundled Linux native libraries required glibc 2.34, which excluded distributions such as
  RHEL 8 and Ubuntu 20.04. They are now built in an Ubuntu 20.04 container and require glibc 2.31
  ([#20](https://github.com/zolotov/oniguruma-bindings/issues/20),
  [#21](https://github.com/zolotov/oniguruma-bindings/pull/21)).
- Fixed the manifest of the `slim` jar, which was assembled from an unresolved Gradle provider.

## [1.0.1] - 2025-07-22

### Fixed

- The module descriptor did not export `me.zolotov.oniguruma`, so the artifact was unusable from
  the module path: JPMS consumers failed to compile against it. The package is now exported, and
  the module descriptor itself is compiled and tested on the module path
  ([#19](https://github.com/zolotov/oniguruma-bindings/issues/19)).

## [1.0.0] - 2025-05-18

First release, published to Maven Central as `me.zolotov.oniguruma:oniguruma-jni`.

### Added

- A JNI binding to the Oniguruma regular expression library, implemented in Rust on top of the
  [`onig`](https://crates.io/crates/onig) crate, targeting Java 17 and exposing the JPMS module
  `me.zolotov.oniguruma`.
- Byte-oriented matching over UTF-8: `createRegex`, `createString` and `match` return group offsets
  as an `int[]`, which is the shape `textmate-core` consumes.
- Native libraries for macOS, Linux and Windows on both x86_64 and aarch64, bundled in the default
  `full` artifact and loaded via `Oniguruma.createFromResources()`.
- A `slim` artifact for consumers that ship the native library themselves, loaded via
  `Oniguruma.createFromFile(path)` and selected through the `me.zolotov.oniguruma.packaging`
  Gradle attribute. Individual per-platform native artifacts are also published and selectable
  through the `me.zolotov.oniguruma.platform` attribute.
- Errors from Oniguruma are propagated to the JVM as exceptions rather than crashing the process.
