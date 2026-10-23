# ADR 0082 — One zipped distribution

- **Status:** Accepted
- **Date:** 2026-10-15
- **Amends:** ADR 0004 §3 (unpacked folders, no archives) and its deferred alternative "Archives (`.tar.gz`, `.zip`)"; ADR 0034 §9 (a folder per OS)
- **Amended by:** ADR 0083 (the zip also holds the user `README.md`, `LICENSE` and `NOTICE`)

## Context

`build-dist.sh` wrote two folders, `dist/leia-<version>-linux-x86_64/` and `dist/leia-<version>-windows-x86_64/`. Both held the same three files, `leia.jar`, `leia` and `leia.cmd`: the jar doesn't depend on the platform (ADR 0034 §9), and each folder carried both scripts anyway. Two folders only duplicated the jar, and a folder isn't something to hand out; an archive is. The archive must keep the executable bit of `leia`, and adding a file to it (a README and a license for users come next) should take one line.

## Decision

1. **One archive.** `dist/leia-<version>.zip` holds the folder `leia-<version>/` with `leia.jar`, `leia` (mode 755) and `leia.cmd` (CRLF line endings, as in the repository). Unpacking it gives that one folder, which runs from anywhere. The name carries no OS.
2. **Built by Gradle.** `leia-tui`'s task `leiaZip` writes it to `leia-tui/build/dist/`, setting the mode of `leia` as `leiaFolder` does; Gradle's `Zip` stores each entry's unix permissions. `build-dist.sh` runs it and copies the zip into a fresh `dist/`, which holds nothing else. `leiaZip` isn't part of `assemble`; `build-dist.sh` stays the release build.
3. **Unchanged:** Linux and Windows on x86_64, no bundled Java, macOS untested and unsupported (ADR 0004, ADR 0034), and no `runtimes/` in the distribution (ADR 0073 §8): `D` on the start screen or `leia --download-runtimes` installs it.

## Alternatives considered

- **Keep a folder per OS.** Rejected: the same bytes twice, and still not an archive.
- **A `.tar.gz` for Linux beside the zip.** Rejected: two archives of the same thing. Unzipping works on both platforms, and Windows has no tar by default to rely on.
- **Pack the zip in `build-dist.sh` with `zip` or Python.** Rejected: it needs a tool that isn't always there, and has to set the unix mode itself.
- **The `application` plugin's `distZip`.** Rejected: its `bin/` and `lib/` layout and generated start scripts would replace leia's own scripts (ADR 0034 §8).

## Consequences

- One file to publish. A user unzips it and runs `leia-<version>/leia` or `leia.cmd`.
- New files for the distribution go into `leiaZip`'s `into("leia-${project.version}")` block.
