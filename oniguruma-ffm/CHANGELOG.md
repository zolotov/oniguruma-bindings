# Change Log

## [Unreleased]

## [1.0.0] - 2026-07-30

### Added

- Initial release of `oniguruma-ffm`: an Oniguruma binding built on the Java Foreign Function &
  Memory API, requiring Java 25 and carrying no JNI glue of its own. Bundles Oniguruma 6.9.10 for
  macOS, Linux and Windows on x86_64 and aarch64.
- A `slim` variant for consumers that supply the native library themselves; select it with the
  `me.zolotov.oniguruma.packaging` attribute.

[Unreleased]: https://github.com/zolotov/oniguruma-bindings/compare/oniguruma-ffm-1.0.0...HEAD
[1.0.0]: https://github.com/zolotov/oniguruma-bindings/commits/oniguruma-ffm-1.0.0
