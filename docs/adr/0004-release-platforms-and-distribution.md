# ADR 0004 — Release platforms and distribution

- **Status:** Accepted, amended by ADR 0034 (one `leia.jar` with the scripts `leia` and `leia.cmd`, and no Rust build; still Linux and Windows on x86_64, and no bundled Java)
- **Date:** 2026-10-05

## Context

leia ships as two pieces: the native `leia` TUI (Rust) and `leia-server.jar`, which needs a Java 25+ runtime. The TUI finds the jar next to its executable and finds the JVM through `--java`, `$JAVA_HOME` or `PATH` (see the TUI CLI contract in `AGENTS.md`). The task list left two questions open:

- **macOS:** cross-compile from Linux (`cargo-zigbuild` needs a macOS SDK) or build natively on a CI macOS runner?
- **Distribution:** plain folders like `../json-rpc-sample/dist`, or also a bundled JRE (jlink), and in what packaging?

The Linux and Windows builds already work from one Linux machine: `cargo build` for Linux and `cargo zigbuild` for Windows (`build-dist.sh`).

## Decision

1. **Platforms: Linux and Windows on x86_64.** No macOS build.
2. **No bundled Java.** Neither a jlink image nor a full JRE is shipped. Users bring a Java 25+ runtime and point leia at it with `--java`, `JAVA_HOME` or `PATH`.
3. **Unpacked folders.** `build-dist.sh` writes `dist/leia-<version>-linux-x86_64/` and `dist/leia-<version>-windows-x86_64/`. Each folder holds only the executable and `leia-server.jar`. No `.tar.gz` or `.zip` archives for now.

## Alternatives considered

- **macOS by cross-compiling** with a macOS SDK under `cargo-zigbuild`. It needs the SDK, has licensing questions, and no one would test the result. Rejected for now.
- **macOS on a CI runner.** Needs a CI setup the project doesn't have yet. Rejected for now.
- **A jlink-bundled runtime per OS.** Makes leia run without Java installed, but adds roughly 40–60 MB per platform, needs a jlink step per target OS, and the bundled runtime would need security updates. Users of a Lucene inspector already have a JVM. Rejected.
- **Archives (`.tar.gz`, `.zip`).** Not needed while there is no download page or release process. Deferred.

## Consequences

- `build-dist.sh` is the release build. It runs on Linux and needs `cargo-zigbuild` with zig for Windows.
- macOS users can build the TUI from source (`cargo build --release`) and use `leia-server.jar` from either folder, since the jar runs on any OS.
- The "Java 25 or newer" requirement stays in the README and in the error messages `leia` prints when the JVM is missing or too old.
- Adding macOS, archives or a bundled runtime later needs a new ADR that supersedes the affected point.
