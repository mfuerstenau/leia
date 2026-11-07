# ADR 0125 — Lucene jars from the local Maven and Gradle caches

- **Status:** Accepted
- **Date:** 2026-11-07
- **Amends:** ADR 0073 §7 (a jar is copied from a local cache before it is downloaded); ADR 0124 (the mirrors are asked only for the jars no cache has); AGENTS.md, the CLI contract

## Context

The runtimes' Lucene jars are downloaded from Maven Central or a mirror (ADR 0124), about 32 MB. Many of leia's users build Java software, and every developer of leia builds leia itself, so the same jars are often on disk already: in Maven's local repository, or in Gradle's cache. Every jar is checked against the size and SHA-256 that leia's build recorded (ADR 0073), so a cached copy that matches is the same jar as a download.

## Decision

1. **Where leia looks**, in this order; only folders that exist count:
   1. **Maven's local repository:** `-Dmaven.repo.local`; else the `<localRepository>` of `~/.m2/settings.xml`, with `${user.home}`, `${env.X}` and other system properties expanded; else `~/.m2/repository`. The jar is at its Maven path.
   2. **Gradle's cache:** `$GRADLE_USER_HOME`, else `~/.gradle`, then `caches/modules-2/files-2.1/<group>/<artifact>/<version>/<sha1>/<file>`. Every `<sha1>` folder is a candidate.
2. **Only a match counts.** A candidate is taken if it is a regular file with the listed size and SHA-256. One that doesn't match is skipped.
3. **In the plan.** `plan` looks the jars up while it hashes what `runtimes/` has, still without the network and without writing. A `Download` carries its local copy. The download size counts only the jars without one.
4. **In the install,** a local copy is copied through a `.part` file and checked again, since the cache may have changed since the plan. On a mismatch or a read error, the jar is downloaded from the mirrors as before, so a broken cache never fails a runtime. The jars are copied, not linked: the runtimes folder outlives the caches, a link into a cache would break when Gradle cleans it, and Windows locks the jars that are loaded.
5. **Shown.**
   - The listing of `--download-runtimes` prints `copied from <file> (<cache>)` in place of a jar's URL, and the counts and sizes of the copied and the downloaded jars. The line per jar says `copied, <size>, from <cache>`.
   - The `D` window lists the copied jars first in a runtime's details ("Copied from local caches, not downloaded"), leaves them out of the row's jar count, its size and the gauge, and shows `copied from <cache>` in the progress.
6. **On by default, without asking:** it is the same checked jar, and no network is used. `-Dleia.download.local=false` turns it off.
7. `settings.xml` is read with the JDK's DOM, DOCTYPE declarations disallowed and secure processing on, and an error handler that prints nothing (AGENTS.md rule 4). A file that can't be parsed is logged at INFO and skipped.

## Alternatives considered

- **Maven only.** Rejected: Gradle's layout is internal, but it has been stable for years, and every developer of leia has the jars there. A layout that changes only means a miss, and a download.
- **Ask first,** or off by default. Rejected: nothing is trusted that a download wouldn't be, and nothing is fetched.
- **Link instead of copy.** Rejected; see §4.

## Consequences

- An offline install works wherever the caches hold the jars.
- `RuntimeInstall` takes a `LocalCaches`; the tests' constructors use `LocalCaches.none()`, so they never see the developer's own caches.
