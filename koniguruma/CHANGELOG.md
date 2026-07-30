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
