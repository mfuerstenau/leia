# ADR 0123 — A draft release for every release tag

- **Status:** Accepted
- **Date:** 2026-11-03
- **Amends:** ADR 0004 and ADR 0082 (who builds the zip for a release)

## Context

The release zip came from `build-dist.sh` on a developer's machine, with the version from `gradle.properties`, which says `1.0-SNAPSHOT`. Nothing made sure the tests had passed on the commit that was released.

## Decision

1. **Trigger:** pushing a tag `release/vMAJOR.MINOR.HOTFIX` (`.github/workflows/release.yml`). Other tags do nothing.
2. **Version:** the tag's, passed as `-Pversion`, so `leia-<version>.zip` and `leia --version` carry it and `gradle.properties` stays at the snapshot.
3. **Tests first:** `./gradlew build` (all tests, `jarsTest`, `serverTest`, `spotlessCheck`, the jar checks) runs before the zip is packed; a failure ends the workflow with no release.
4. **Assets:** `leia-<version>.zip` (the one zip for Linux and Windows, ADR 0082) and `leia-<version>-sources.zip` (`git archive` of the tagged commit).
5. **Draft:** the release is created as a draft with generated notes. A person publishes it on GitHub; the workflow never does. The job that writes to the repository is separate from the one that runs the build and tests, and holds the only `contents: write`.

## Consequences

- To release: `git tag release/v1.2.3 && git push origin release/v1.2.3`, then check the draft and publish it.
- A re-run on the same tag replaces the assets of an existing draft instead of failing.
- The tag's commit must be an ancestor of `origin/main`, else the workflow fails before building anything. A commit that is on `main` only after a squash or rebase isn't: tag the commit as it is on `main`.
