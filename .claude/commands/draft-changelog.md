---
description: Draft Unreleased changelog entries for a module from the commits since its last release
argument-hint: oniguruma-jni | oniguruma-ffm
allowed-tools: Bash(git log:*), Bash(git show:*), Bash(git diff:*), Bash(git describe:*), Bash(git tag:*), Bash(gh pr view:*), Bash(gh pr list:*), Read, Edit
---

Draft changelog entries for the module `$1` into the `## [Unreleased]` section of
`$1/CHANGELOG.md`.

This produces a **draft for a human to edit**, not final release notes. Prefer leaving a `TODO`
over guessing: an entry you cannot ground in the diff is worse than no entry.

## Gather

1. Find the last release tag for the module: `git describe --tags --abbrev=0 --match "$1-*"`.
   `oniguruma-jni` has no prefixed tag yet — its pre-split releases are tagged bare (`2.0.0`), so
   fall back to the newest of those. `oniguruma-ffm` has never been released; if there is no tag,
   use the commit that introduced the module.
2. `git log <tag>..HEAD --oneline -- $1/` for the commits that touched the module. Also check
   `gradle/libs.versions.toml` and `$1/native/` — dependency and native-library changes ship to
   users even though they may not look like source changes.
3. **Read the actual diffs**, not just the subjects. `git show <sha> -- $1/` for anything that
   looks behavioural. The subject line is what makes generated changelogs useless; the diff is
   where the semantics are. Where a PR number is available, `gh pr view <n>` often explains the
   *why* that no commit records.

## Write

The `## [Unreleased]` section starts out with no group headings. Add only the `###` groups you have
entries for, drawn from **Breaking**, **Added**, **Changed**, **Fixed**, **Performance**, and keep
them in that order. Do not add a group you are going to leave empty.

Each entry states what a *consumer of the library* observes:

- **Fixed** — the symptom and the condition that triggers it, not the mechanism.
  Good: ``Fixed `search()` returning stale group offsets when a compiled pattern was reused across
  threads.``
  Bad: `Optimize JNI: critical array access, Region cache, cached exception class`.
- **Breaking** — what breaks, plus the migration. Name the old and new symbol or coordinate.
- **Performance** — quantify from the JMH benchmarks in `benchmarks/` if a number exists; say
  which benchmark. An unquantified performance claim is noise.
- **Added** — the public API surface added, named. If it is not in `module-info.java`'s exported
  packages, it is not an addition users can see.
- Platform, toolchain and minimum-JDK changes are user-visible even when they touch no source:
  a new supported architecture, a raised glibc floor, a Java baseline bump all belong here.

Rules:

- Reference the PR as `([#63](https://github.com/zolotov/oniguruma-bindings/pull/63))` where one
  exists. Do not fabricate numbers.
- Collapse dependency bumps into **one** entry under Changed, naming only what a consumer could
  notice: `Updated JUnit 5.10 → 6.1 (test-only)`. Never one bullet per Dependabot PR.
- Omit CI, build-script, benchmark-harness, formatting and internal-refactor commits entirely.
  If a release contains only such commits, say so and add nothing.
- Do not touch released sections, the file title, or the link definitions at the bottom — the
  Gradle plugin owns those.

## Report

After editing, show the resulting `## [Unreleased]` section and list, separately:

- commits you deliberately omitted, with the reason (one line each), so the omissions can be
  challenged;
- entries you could not ground in a diff, marked `TODO`, with the question that needs answering.
