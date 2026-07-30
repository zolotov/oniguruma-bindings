# Change Log

## [Unreleased]

### Added

- Initial version of `koniguruma`: a Kotlin Multiplatform wrapper exposing one common API over the
  oniguruma-bindings backends. The JVM target delegates to `oniguruma-ffm` and requires Java 25
  with native access enabled.
- `wasmJs` target backed by `onig.wasm` via the `vscode-oniguruma` npm package. Under Node.js the
  wasm binary loads from `node_modules` automatically; browsers pass it to
  `createOniguruma(wasmBinary)`. Groups that did not participate in a match are reported clamped
  to the end of non-ASCII subject texts (vscode-textmate behavior) and as `-1, -1` otherwise.
- Native targets (`linuxX64`, `linuxArm64`, `macosX64`, `macosArm64`, `mingwX64`): Oniguruma
  6.9.10 is compiled from the pinned source release into each target's cinterop klib, so
  consumers need no C toolchain and no system `libonig`.
- Android target (minSdk 26) delegating to `oniguruma-jni`. Applications bundle
  `liboniguruma_jni.so` for their ABIs in `jniLibs`; `createOniguruma()` loads it from the
  app's native library directory, with an Android-specific `createOniguruma(context)` overload.
