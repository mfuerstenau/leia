# ADR 0089 — Gradle dependency verification

- **Status:** Accepted
- **Date:** 2026-10-18

## Context

Nothing checked what Gradle downloads. Whatever jar Maven Central or the plugin portal served, or whatever sat in `~/.gradle/caches`, went into the build. That matters beyond the build: `RuntimeListTask` computes the SHA-256 of the jars Gradle resolved for each runtime and writes it into the runtime's `runtime.txt`, and `RuntimeInstall` checks the users' downloads from Maven Central against those hashes (ADR 0073, ADR 0077). A tampered jar at build time would become the hash every user's download is checked against.

SonarQube reports this as kotlin:S6474 on the project, which ADR 0088 left open, since no `resourceKey` matches an issue of the project.

## Decision

1. **`gradle/verification-metadata.xml` pins the SHA-256 of every artifact the build resolves**, with `verify-metadata` on, so poms and Gradle `.module` files are checked as well as jars. It covers every configuration of the root build, of the included `build-logic` (Gradle reads only the root's file for included builds) and of the plugin classpaths: the foojay settings plugin, Spotless and the Palantir Java Format it resolves at execution time, Shadow and kotlin-dsl, the runtimes' folders (`RuntimeFolderJars`), the unbundled analysis modules, the `jarsTest`, test-index and `lucene10World` configurations. Gradle checks on every resolution, also of an artifact that is already in the cache, and fails the build with the artifact's name and coordinates.
2. **Checksums only, no PGP signatures** (`verify-signatures` off).
3. **Sources and javadoc jars are trusted** (`<trusted-artifacts>`, `.*-sources[.]jar` and `.*-javadoc[.]jar`), so that an IDE that fetches them doesn't fail its sync. So are the two other files IntelliJ's sync fetches for the build scripts, which the CLI build never resolves and so never records: the Gradle distribution's sources (`gradle:gradle`, `gradle-*-src.zip`, from services.gradle.org) and the `.module` and `.pom` of every `org.apache.groovy` module (from repo.gradle.org), whose sources it fetches for the Groovy modules Gradle bundles (`groovy`, `groovy-ant`, … and their `groovy-bom`), both matched without a version, so that a Gradle bump doesn't break the sync again. None of them reach a classpath or a runtime folder. The build's own `groovy-bom` 4.0.27 falls under the second rule too; that trusts only its metadata, and every jar it might steer a version to is still checked against its SHA-256.
4. **The file is generated, then reviewed.** After a change to `gradle/libs.versions.toml`, a plugin version or a new configuration:

   ```sh
   ./gradlew --write-verification-metadata sha256 --refresh-dependencies --no-configuration-cache \
     build :leia-app:leiaFolder :leia-server:analysisModules :leia-core:demoIndex
   ```

   The diff should hold only the new `<component>`s of the bumped versions. `--refresh-dependencies` matters: without it the first generation missed a BOM (`kotlinx-coroutines-bom` 1.8.0) that Gradle fetches only on a fresh resolution, and a build without a warm cache then failed. The extra tasks resolve what `build` alone doesn't. Gradle never removes an entry that is no longer used, so before a release, or after large bumps, delete the `<components>` block and regenerate. Gradle rewrites the file and keeps its `<configuration>`, but drops XML comments, so the pointer to this ADR is in `AGENTS.md`, not in the file.
5. **The `sonar-check` skill runs Gradle with `--dependency-verification lenient`.** Its init script adds the SonarQube scanner and JaCoCo, which the build itself doesn't use; the file lists only what the build uses, and lenient mode logs the init script's artifacts instead of failing on them.

### What it doesn't check

- Project dependencies between leia's own modules.
- The toolchain JDKs that foojay downloads into `~/.gradle/jdks`.
- The Gradle distribution, which `distributionSha256Sum` in `gradle/wrapper/gradle-wrapper.properties` pins already.
- The classpath of an init script, such as the `sonar-check` skill's (lenient, see 5).
- leia's own download of the runtimes at run time, which the hashes of `runtime.txt` check; those hashes now come from verified jars.

## Alternatives considered

- **PGP signatures with a keyring in the repository.** Lucene's release managers, and so its signing keys, change with each release, some artifacts are unsigned (so they'd need checksums anyway), and generating the keyring looks keys up on key servers. Rejected for now; checksums catch a jar that changed after it was first resolved, which is the case `runtime.txt` depends on.
- **Lenient mode by default.** It only logs, so it protects nothing. Rejected.
- **Leaving S6474 accepted.** It leaves the `runtime.txt` chain above unchecked. Rejected.
- **Pinning hashes in `RuntimeListTask` only.** It would cover only the runtime jars, not the compilers, plugins and test libraries. Rejected.

## Consequences

- The file holds 298 components, about 130 KiB. A regeneration takes about 20 to 45 seconds with a warm build.
- On a build, the check costs no measurable time: an up-to-date `build` takes 0.63 s with verification off and 0.66 to 0.69 s with it.
- Every version bump needs the regeneration, else the build fails, naming the artifact and pointing to Gradle's troubleshooting page.
- kotlin:S6474 is gone: the main code has no open SonarQube issues.
