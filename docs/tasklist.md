# Task list

The work queue for leia. Check items off (`[x]`) as they land, and keep it aligned with the "Remaining / next" section of the latest entry in `docs/SESSION-SUMMARIES.md`. Finished items move to `docs/tasklist-done.md`.

## Now — move the TUI to TamboUI (feature freeze)

**Feature freeze:** nothing from the backlog below starts until this section is done. Fixes to the Rust TUI are fine; new features are not, in either UI. Settled on 2026-10-09: one JVM, with the TamboUI app calling the server's handlers in-process; launcher scripts `leia`/`leia.cmd` that run `java -jar leia.jar`; no Rust left.

- [ ] Port the screens, each with TamboUI Pilot tests in place of the Rust tests, keeping keys and behaviour:
  - [x] app shell: tabs, open prompt (directory choice, no-reader, recent paths), status line, help, reopen (`R`), the logo; the screens are placeholders until they are ported
  - [x] overview (index stats, fields, term scans); Enter and `/` on a top term come back with the terms and search screens
  - [x] documents: the screen, the viewer, stored values, term vectors, doc values, Enter on a posting of the terms screen and `[`/`]` through the term's docs; the rebuild (`r`, ADR 0029), the filter (ADR 0031/0032) with `b` on the terms screen (`Terms.setFilter`), and the export (`E`, ADR 0030)
  - [x] terms: fields, terms, postings, seek; Enter on an overview top term opens it here
  - [ ] search: parser options, scoring/similarity, sort, More-Like-This, explain, stored scan, doc-values search, hit fields; then `/` on an overview top term searches for it, the first polls of a new job at 10 ms, and on the documents screen `s` search value, `m` similar, `[`/`]` through the hits (`Walk.SEARCH_HITS`), and `b` starting a filter, which only has to call `App.startFilter` (`Documents.Request.SearchDocValues`, `MoreLikeThis`)
  - [ ] analysis: analyzer builder, presets and word sets, analyze; then `a` on the documents screen and in the value viewer comes back (`Documents.Request.Analyze`, `Viewer.keys()`)
  - [x] commits and segments
  - [ ] tools: check index, export terms; then `e` on the terms screen (`Terms.Request.Export`) comes back, with the terms kind of `ExportPrompt` (its document kind is ported)
  - [ ] jars: `X`, the default folder; then the about screen's "X to add jars", the `X jars` key, and "add a jar that has it with X" in the missing-codec hint come back (`Session.withJarHint`)
- [ ] Parity check against `docs/demo-index.md` and the real-server tests that `cargo test -- --ignored` runs now
- [ ] Remove the old path: the whole `tui/` crate (and the Rust steps of the build), `protocol/fixtures/` and the fixture tests, the JSON-RPC transport (`RpcServer`, `Dispatcher`, `RequestScanner`, `LeiaServer`'s stdio loop), the envelope and Jackson setup in `leia-protocol` (`Request`, `Response`, `JsonRpcVersion`, `ProtocolJson`, `ProtocolAnnotationIntrospector`, the Jackson annotations), `protocol/error-codes.json`, and Jackson if nothing else needs it (ADR 0034 §6)
- [ ] Update `AGENTS.md` (architecture, hard rules 2–4 → the one rule of ADR 0034 §6, tech stack, commands, CLI contract), `README.md` and `build-dist.sh` (one `leia.jar` plus `leia` and `leia.cmd`; no cargo, cargo-zigbuild or zig)

## Backlog — future capabilities

Frozen until the TamboUI migration above is done.

From the read-only checklist in `luke-features.md` §7. Ordered roughly by value. Each area will need its own protocol methods, fixtures and probably an ADR for the cross-cutting parts.

- [ ] Search: a date resolution per field (ADR 0026 has one for all fields)
- [ ] Search: a prefix match on sorted, sorted-set and binary doc values (left out of ADR 0033)
- [ ] Analysis: `PerFieldAnalyzerWrapper`, `QueryAutoStopWordAnalyzer` (needs an `IndexReader`), the collation analyzers (need a `Collator`) and `JapaneseCompletionAnalyzer` (left out of ADR 0023)
- [ ] Open options: Elasticsearch/OpenSearch indexes (left out of ADR 0018): their codecs need the server's `lib/`, which has its own `lucene-*.jar` of another version; skipping those jars, or saying which ones clash, untested without an installation
- [ ] Commits: per-field postings, doc values and KNN vectors formats (from each segment's field infos; ADR 0009 leaves them out)
- [ ] Tools: check only the selected index or segments, and the thread count (left out of ADR 0022)
- [ ] Tools: export terms with `totalTermFreq` and a marker for binary terms (left out of ADR 0022, which keeps Luke's format)
- [ ] Misc: log view with level filter, cross-screen navigation (`luke-features.md` §5), themes, about
- [ ] Improvements over Luke (§6): KNN vector inspection/search, point values (also a document's own, which ADR 0029 and the JSON export of ADR 0030 leave out), per-segment doc view, deleted-doc inspection, readable binary values, highlighting, cancellation of long operations

## Open questions

None right now. Settled: release platforms and distribution (ADR 0004).
