# Task list

The work queue for leia. Check items off (`[x]`) as they land, and keep it aligned with the "Remaining / next" section of the latest entry in `docs/SESSION-SUMMARIES.md`. Finished items move to `docs/tasklist-done.md`.

## SonarQube quality gate

Goal: the quality gate passes on new code. The fix of 2026-10-18 (`88ea82a`, `8b696a4`) skipped rules by choice and left false positives open, and nothing is recorded in SonarQube, so each scan in a fresh docker container counts them again (see that day's entry in `docs/SESSION-SUMMARIES.md`).

- [ ] Evaluate how to whitelist the accepted ones (ADR 0088 lists them, with a scope and a reason each, and leaves its mechanism section to this item) so that it persists in the repository and applies to a new container: `sonar.issue.ignore.multicriteria` (rule + file pattern) in the Gradle `sonar` properties, `@SuppressWarnings("java:S…")` or `// NOSONAR` at the line, or a quality profile kept in the repository and restored into the container. Pick one, probably as an ADR, with a reason per accepted rule
- [ ] Make the quality gate pass with it, including duplication on new code (10.8 %, mostly the helpers shared by the Lucene 5/7/9 runtimes), and have the `sonar-check` skill apply the mechanism and report the gate's status

## Lucene 2 to 10 (ADR 0044)

Goal: every index from Lucene 2.1 to 10 opens in one leia, each read by a relocated Lucene that can read it, with the features of that Lucene and the rest switched off. The spike did Lucene 2.x and 3.x on Lucene 3.6.2 (`leia-lucene3`). Each Lucene major reads its own indexes and those of the major before it, so four runtimes besides Lucene 10 are needed; all four are done.

The lane is done. What each runtime can do, and how they differ: `docs/runtimes.md`.

## Backlog — future capabilities

From the read-only checklist in `docs/luke-features.md` §7. Ordered roughly by value. Each area will need its own records, handlers and probably an ADR for the cross-cutting parts.

- [ ] Open options: Elasticsearch/OpenSearch indexes (left out of ADR 0018): their codecs need the server's `lib/`, which has its own `lucene-*.jar` of another version; skipping those jars, or saying which ones clash, untested without an installation
- [ ] Tools: check only the selected index or segments, and the thread count (left out of ADR 0022)
- [ ] Tools: export terms with `totalTermFreq` and a marker for binary terms (left out of ADR 0022, which keeps Luke's format). Trie-encoded terms of 4.x to 7.x (7 bits a byte, so valid UTF-8) are written raw today, and one can hold a newline, which breaks the line format
- [ ] Misc: themes
- [ ] Misc: about
- [ ] Build: Gradle dependency verification (`verification-metadata.xml`, SonarQube S6474), as an ADR: what it checks, how the file is kept up to date with the version catalog, and what it costs the build (accepted for now in ADR 0088)

## Bugs

None open.

## Open questions

None right now. Settled: release platforms and distribution (ADR 0004).
