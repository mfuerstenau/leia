# ADR 0058 — Runtime interface 10: term queries, and search split into parts

- **Status:** Accepted
- **Date:** 2026-10-13
- **Amends:** ADR 0049 §4 (the runtime interface); ADR 0044 §4 (the features of an index)

## Context

On an index that a runtime reads (3.6, 5.5, 7.7, 9.12), three places were greyed out because they check `Feature.SEARCH`, which only Lucene 10 reports: the terms screen's `s`, the overview's `/ search term` and the search screen's TERM mode. Every runtime's Lucene has `TermQuery`, so a term query needs one method in the runtime interface.

`SEARCH` stands for all of search: every kind of query, similarity and sort, highlighting aside. The runtimes have only had `CLASSIC_SEARCH` (ADR 0044), with KNN queries under it (ADR 0049). More parts of search will land on the runtimes one at a time, 9.12 first, and each needs a feature the TUI can gate on.

## Decision

1. **`Search.term(field, term)`** returns a `ParsedQuery` for exactly one term, neither analyzed nor split, as the protocol's `TermQuery` is. The term is bytes, as the runtimes' terms are; 3.6, whose terms are strings, decodes them as UTF-8 (as its `Terms` does). The query goes to `search`, `matches` and `explain` like a parsed one, which unwrap any query of their own runtime unchanged.
2. **The runtimes.** 3.6: `new TermQuery(new Term(field, text(term)))`; 5.5, 7.7 and 9.12: `new TermQuery(new Term(field, new BytesRef(term)))`, the same in each facade (ADR 0050).
3. **The server.** `RuntimeResults.features` adds `TERM_SEARCH` with the `search()` group. `parsedQuery`, which `startSearch`, `startFilter` and `startExplain` share, gains a branch for `SearchQuery.term`: `checkField` (`FIELD_NOT_FOUND`), then the term through `TermParams.term`, which refuses a binary term that isn't hex with `INVALID_PARAMS`. So a term query searches, filters (`b`) and explains (`x`) on a runtime with no handler changed.
4. **`SEARCH` splits into one feature per part.** A runtime reports the parts of search it has (`CLASSIC_SEARCH`, `TERM_SEARCH`, and those to come), and `SEARCH` means all of them, which only Lucene 10 has until every part has landed on a runtime. The TUI gates each use of a part on `SEARCH || <part>`, as it gated the classic mode on `SEARCH || CLASSIC_SEARCH`: the terms screen's `s`, the overview's `/` and the search screen's TERM mode on `TERM_SEARCH`, and the search screen itself on any part. What no part covers yet, such as the similarity and sort popup, stays on `SEARCH`.
5. **The runtime's similarity.** A term query is scored with the runtime's default similarity, as a classic query is (ADR 0057 §4): the similarity and sort of the params are ignored on a runtime.
6. **Runtime interface 10.** `LuceneRuntime.API_VERSION` is 10, so a runtime jar built for interface 9 is refused (ADR 0044).

## Alternatives considered

- **Reporting `SEARCH` on the runtimes once term queries work**: it would offer the standard parser, More-Like-This, doc values queries, similarities and sorts, which they don't have.
- **Putting term queries under `CLASSIC_SEARCH`**, as KNN queries are (ADR 0049): it would hide which part a runtime has once the parts land at different times, and the TUI couldn't tell them apart.
- **Building the term query from a classic expression** (`field:term`): the classic parser analyzes and escapes, and can't take a binary term such as a trie-encoded number.

## Consequences

- `TERM_SEARCH` is reported on every runtime with a reader and on Lucene 10; `docs/runtimes.md` has its row, and a Differences row for 3.6's UTF-8 decoding.
- The terms screen's `s`, the overview's `/` and the search screen's TERM mode work on every index with a reader.
- The later parts of search (the standard parser, More-Like-This, doc values queries, similarity and sort) each get a feature of their own as they land on a runtime.
