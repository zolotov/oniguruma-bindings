## Releases

Each module now has its own release cadence:

- `oniguruma-jni`: changelog in [`oniguruma-jni/CHANGELOG.md`](./oniguruma-jni/CHANGELOG.md), tags and GitHub releases as `oniguruma-jni-X.Y.Z`
- `oniguruma-ffm`: changelog in [`oniguruma-ffm/CHANGELOG.md`](./oniguruma-ffm/CHANGELOG.md), tags and GitHub releases as `oniguruma-ffm-X.Y.Z`

### Changelog

Changelog entries are written by hand, because a commit subject records what the author did to the
source tree rather than what a consumer of the library gets. Describe the observable change: the
symptom and its trigger for a fix, the migration for a breaking change, a benchmark number for a
performance claim.

Add entries to the `## [Unreleased]` section of the module's `CHANGELOG.md` as you go, under
whichever of `### Breaking`, `### Added`, `### Changed`, `### Fixed` and `### Performance` apply.
For a starting point, `/draft-changelog <module>` in Claude Code reads the commits and diffs since
the last release and drafts entries — the draft always needs editing before it ships.

Preview what the next release will publish:

```bash
./gradlew :oniguruma-jni:getChangelog --unreleased --no-header
cat oniguruma-jni/build/reports/changelog/latest-release-body.md
```

The `Release` workflow promotes that section to a version section
([`patchChangelog`](https://github.com/JetBrains/gradle-changelog-plugin)) and hands the same text
to the GitHub release, so nothing is generated at release time. An empty `Unreleased` section fails
the release before anything is published.

The same workflow runs `updateReadmeVersion`, which rewrites the dependency coordinates in the
module README to the version being released and commits them alongside the changelog, so the
install snippets always name a version that exists.
