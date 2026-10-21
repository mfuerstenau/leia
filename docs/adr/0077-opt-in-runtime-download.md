# ADR 0077 — Opt-in runtime download

- **Status:** Accepted
- **Date:** 2026-10-15
- **Amends:** ADR 0073 §7 (`--download-runtimes` asks first, and takes `--yes`); AGENTS.md, the CLI contract

## Context

`leia --download-runtimes` (ADR 0073 §7) started fetching about 32 MB of Lucene jars from Maven Central as soon as it ran. The user never saw what was fetched, from where, or what it was checked against, and had no chance to say no.

## Decision

1. **Plan, then install.** `RuntimeInstall` first reads each runtime's adapter and list from `leia.jar` and hashes the Lucene jars already in `runtimes/`, without the network and without writing. A Lucene jar that is missing or doesn't match the list's SHA-256 is to be downloaded. A `leia.jar` that lacks a resource fails here, with exit 1, before anything is asked.
2. **The listing.** If something is to be downloaded, stdout gets every such jar as `<folder>/<jar>, <size>`, its URL and its SHA-256, after a line saying that these are the official Lucene jars of Maven Central, unchanged, checked against the SHA-256 and size that leia's build recorded. With `-Dleia.download.url`, it names that repository as well. It ends by saying that leia's own jars, the adapters and leia-core, come from `leia.jar` and aren't downloaded, and how many matching jars are kept.
3. **Consent.** Then it asks `Download <n> jars, <size> in all? [y/N] ` on the terminal (`System.console()`, when `isTerminal()`). Only `y` or `yes`, in any case and trimmed, counts as yes. Any other answer, end of input included, prints `leia: nothing downloaded` and exits 1 with `runtimes/` untouched: the runtimes stay uninstalled, so it isn't a success (`apt` exits 1 on `Abort.` too). Without a terminal, nothing is asked: it exits 1 with a message that names `--yes`.
4. **`--yes`** downloads without asking, after the listing, for scripts and CI. It goes only with `--download-runtimes` (exit 2 otherwise), and is the one option that may go with it.
5. **Nothing to download, nothing asked.** When every Lucene jar is there and matches, it says so and goes on: writing leia's own jars and removing stale ones need no network.
6. The install itself is ADR 0073 §7's, driven by the plan: the downloads, their checks, the temporary files, the kept and removed jars and the error messages stay.

## Alternatives considered

- **Download what is missing without asking,** as before. Rejected: the user never sees what is fetched.
- **Ask, without `--yes`.** Rejected: scripted installs and CI have no terminal; `yes | leia --download-runtimes` wouldn't work either, as no terminal means no question.
- **Exit 0 when the user says no.** Rejected: the runtimes aren't installed, and a script should see that.

## Consequences

- An install script needs `--yes`.
- The listing and the question go to stdout, the refusals to stderr.
- `RuntimeInstall` takes a `Prompt`, so the tests answer the question without a terminal.
