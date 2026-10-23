# ADR 0083 — A user README and a license in the distribution

- **Status:** Accepted
- **Date:** 2026-10-15
- **Amends:** ADR 0082 §1 (what the zip holds)

## Context

`dist/leia-<version>.zip` (ADR 0082) held `leia.jar`, `leia` and `leia.cmd`, and nothing that says how to use them. The repository's `README.md` is long and written for development: building, the screens key by key, how it works. The repository had no license, so nobody could use leia under any terms. leia's runtime adapter jars, which `leia.jar` embeds, contain code adapted from Apache Lucene (the date range handling of the query parsers in `leia-lucene3`, `leia-lucene5`, `leia-lucene7` and `leia-lucene9`), and `leia.jar` bundles third-party libraries (TamboUI, JLine, Jackson, Dagger, the inject APIs, JSpecify), whose licenses ask for attribution.

## Decision

1. **Apache License 2.0.** leia is licensed under the Apache License, Version 2.0, the canonical text in `LICENSE` at the repository root. It is Lucene's own license, so the adapted Lucene code needs nothing beyond its notice.
2. **A `NOTICE`** at the root, in the Apache style: leia's copyright; "This product includes software developed at The Apache Software Foundation" with Apache Lucene's copyright, naming the adapted classes; the third-party software in `leia.jar` with its version and license; and that the Lucene jars in `runtimes/` aren't part of the distribution, but downloaded unchanged from Maven Central with their own LICENSE and NOTICE (ADR 0073).
3. **A user README** in `leia-tui/src/dist/README.md`, Markdown, for usage only: what leia is and that it is read-only, requirements, start, the runtimes and what is downloaded (only the official Lucene jars, checked against SHA-256 and size; leia's adapter jars come out of `leia.jar`), what each runtime can do, the options, extension jars, the global keys, the files, the license and the disclaimer.
4. **A condensed capability matrix.** The README's table `| Can | 3.6.2 | … |` groups the `Feature`s into rows in user terms. `RuntimeFeaturesTest.DistReadme` checks it against the feature table of `docs/runtimes.md` (which the test already checks against `openIndex`): the rows are those the test maps to features, they cover every `Feature` exactly once, the columns are the feature table's, and a cell is `✓…` exactly when the runtime has all of the row's features and `–` when it has none; a row whose features a runtime has only in part must be split. A new `Feature` thus fails the test until the README has a row for it.
5. **Packed by `leiaZip`.** Its `into("leia-${project.version}")` block adds `src/dist` and the root's `LICENSE` and `NOTICE`. The zip unpacks to `leia-<version>/` with `leia.jar`, `leia`, `leia.cmd`, `README.md`, `LICENSE` and `NOTICE`.

## Alternatives considered

- **MIT or BSD.** Shorter, but without Apache 2.0's patent grant, and leia already carries Lucene's Apache 2.0 code and its NOTICE.
- **GPL.** Rejected: a copyleft license for a tool built from Apache-licensed parts, which would also keep it out of projects that bundle Lucene under Apache 2.0.
- **The full feature table in the README.** Rejected: 28 rows of `Feature` names mean little to a user; the condensed rows say what a screen offers, and link to `docs/runtimes.md` for the rest.
- **Generating the README from `docs/runtimes.md` and `Cli`.** Rejected for now: a generator for one file, which would still need the prose written by hand; the test keeps the part that drifts, the matrix, in line.
- **Pack the repository's `README.md`.** Rejected: it is for development, and its links into the repository don't work in the zip.

## Consequences

- leia has a license, and the distribution says how to use it.
- A change to the runtimes' features needs the README's matrix too, which the test enforces; a new CLI option or global key needs the README by hand.
