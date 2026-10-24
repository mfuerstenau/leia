# Task list

The work queue for leia. Check items off (`[x]`) as they land, and keep it aligned with the "Remaining / next" section of the latest entry in `docs/SESSION-SUMMARIES.md`. Finished items move to `docs/tasklist-done.md`.

## SonarQube quality gate

Goal: the quality gate passes on new code. The fix of 2026-10-18 (`88ea82a`, `8b696a4`) skipped rules by choice and left false positives open, and nothing is recorded in SonarQube, so each scan in a fresh docker container counts them again (see that day's entry in `docs/SESSION-SUMMARIES.md`).

The lane is done: on new code the gate passes, and the accepted rules are whitelisted in the repository (ADR 0088).

## Lucene 2 to 10 (ADR 0044)

Goal: every index from Lucene 2.1 to 10 opens in one leia, each read by a relocated Lucene that can read it, with the features of that Lucene and the rest switched off. The spike did Lucene 2.x and 3.x on Lucene 3.6.2 (`leia-lucene3`). Each Lucene major reads its own indexes and those of the major before it, so four runtimes besides Lucene 10 are needed; all four are done.

The lane is done. What each runtime can do, and how they differ: `docs/runtimes.md`.

## Backlog — future capabilities

From the read-only checklist in `docs/luke-features.md` §7. Ordered roughly by value. Each area will need its own records, handlers and probably an ADR for the cross-cutting parts.

- [ ] Open options: `lucene-suggest` in `runtimes/lucene7` and `runtimes/lucene5`, for the completion fields of Elasticsearch 5 and 6 shards (as `runtimes/lucene9` has it for Elasticsearch 7); untested, no such shards tried
- [ ] Open options: Elasticsearch 8.0 to 8.6 with `--server`, whose Lucene 9.0 to 9.4 lacks the KNN classes that `Lucene9Index` names throughout (ADR 0090)
- [ ] Open options: OpenSearch's k-NN indexes, whose codec is in the k-NN plugin, not in `lib/` (left out of ADR 0090)
- [ ] Tools: check only the selected index or segments, and the thread count (left out of ADR 0022)
- [ ] Tools: export terms with `totalTermFreq` and a marker for binary terms (left out of ADR 0022, which keeps Luke's format). Trie-encoded terms of 4.x to 7.x (7 bits a byte, so valid UTF-8) are written raw today, and one can hold a newline, which breaks the line format
- [ ] Misc: themes
- [ ] Misc: about

## Bugs

None open.

## Open questions

None right now. Settled: release platforms and distribution (ADR 0004).
