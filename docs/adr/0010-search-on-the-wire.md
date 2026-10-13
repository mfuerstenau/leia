# ADR 0010 — Search on the wire

- **Status:** Accepted
- **Date:** 2026-10-06

## Context

Luke's Search tab parses a query expression, runs it and pages through the hits with their stored fields. A hit can be explained or opened in the Documents tab (`docs/luke-features.md` §4.3). Settings cover the query parser, the analyzer, the similarity, the sort, the fields to load and More-Like-This. This ADR covers the first slice: classic query parser syntax, a raw term query, paging, exact or approximate total hits, stored fields per hit, and explain. Parser options beyond the default field and operator, the standard query parser with points, similarity, sort and More-Like-This come later, and each can be added without breaking the methods below.

Several questions are new:

- **Long-running work.** Most searches take milliseconds. But a leading wildcard, a regular expression or a fuzzy query enumerates the terms of a whole field, and an exact hit count visits every match. On a large index these take seconds or more, so by hard rule 7 search is a job (ADR 0005). Explaining one document rewrites the query too, and a fuzzy query's rewrite scans the field's terms, so explain can also take long.
- **Cancellation.** Lucene has no cancel flag, but it has `QueryTimeout`. `IndexSearcher.setTimeout` checks it while collecting, and `ExitableDirectoryReader` checks it while enumerating terms, points and doc values, including during the rewrite. Both stop cooperatively. Neither interrupts a thread.
- **Server state.** Luke keeps the query and the hits seen so far between pages. Every leia method except jobs is stateless (ADR 0007).
- **The query on the wire.** Term query mode must accept any term, including terms with a `:` (Luke splits `field:term` on the first `:`) and binary terms from the overview's top terms.
- **The analyzer.** The Analysis screen does not exist yet.

## Decision

1. **Two jobs: `startSearch` / `getSearch` and `startExplain` / `getExplain`** (ADR 0005). The `start` methods check their params synchronously, so a syntax error, an unknown field or a document id out of range fails at once, before any job exists. `cancelJob` stops both.
2. **The query is one of several alternatives**, `SearchQuery {classic?, term?}`. Exactly one must be present, otherwise `INVALID_PARAMS`. This is the same pattern as the envelope's `result` / `error`. The alternatives are:
   - `classic {expression, defaultField, defaultOperator}`: Lucene's classic `QueryParser` with `StandardAnalyzer` and the parser's defaults. `defaultOperator` is `OR` or `AND`, Lucene's `QueryParser.Operator`.
   - `term {field, term}`: a `TermQuery` on the term exactly as given, as a byte string (`BytesValue`, ADR 0007). Nothing is analyzed or split.

   Later parsers (the standard query parser with point fields) become further alternatives with their own options, so a new parser does not change `classic`. An analyzer param waits for the Analysis ADR.
3. **Stateless offset paging.** `startSearch {query, offset, limit, exactTotalHits, fields?}` collects the top `offset + limit` hits and returns the ones from `offset` on. `limit` is 1 to 1000. `offset + limit` is at most 10,000, as Elasticsearch's default `max_result_window`, so the collector's queue stays small. Anything else is `INVALID_PARAMS`. Each page runs the query again. Ties keep Lucene's order (lower document id first), so pages don't overlap.
4. **Total hits as Lucene counts them.** With `exactTotalHits`, every match is counted. Otherwise counting stops after 1000 matches, as in Luke, and the result says `GREATER_THAN_OR_EQUAL_TO`. `totalHitsRelation` mirrors Lucene's `TotalHits.Relation`.
5. **The result** (`SearchResults`) has:
   - the parsed query and the rewritten query, as `Query.toString()`;
   - the total hits and their relation;
   - the hits of the page, each with its document id, its score and its stored fields;
   - how long the search took, in milliseconds.

   Scores are `float` in Java and `f64` in Rust. Every `f32` fits in an `f64`, and a JSON number written from a `float` reads back unchanged. Deleted documents never match.
6. **Stored fields per hit, cut short.** `fields` names the stored fields to load. Without it, every stored field is loaded. Values use `StoredValue` (ADR 0008), cut at 256 bytes with `fullLength` set. The full value is one `getDocument` away. With 100 hits of 30 fields, a page stays under a megabyte.
7. **Explain returns Lucene's `Explanation` tree.** `startExplain {query, docId}` explains why the document matches or not. Explaining a document that doesn't match is how you find out why. Each node is `{match, value, description, details}`. `value` is the number as text (`Number.toString()`), because explanations mix floats (scores) and longs (document counts).
8. **Cancellation through `QueryTimeout`.** Each job searches a new `IndexSearcher` over an `ExitableDirectoryReader` that wraps the open reader, with the same `QueryTimeout` passed to `setTimeout`. The timeout asks the job's `ScanProgress` (ADR 0005), so a cancelled job stops while rewriting, enumerating terms or collecting. The wrapper is never closed, because closing it would close the index's reader. The search job reports the number of documents before the segment being searched as progress. The query cache is off, so a repeated search shows its real cost.
9. **Error code `INVALID_QUERY` (-32012).** `startSearch` and `startExplain` fail with it when the expression doesn't parse. A search job fails with it when the query has too many clauses after rewriting. An unknown `defaultField`, term field or field in `fields` is `FIELD_NOT_FOUND`. Fields named in the expression are not checked, because Lucene doesn't check them either.

## Alternatives considered

- **Synchronous search.** Simpler, and quick in the common case, but a wildcard or fuzzy query on a large index would freeze the TUI with no way to stop it. That is what ADR 0005 exists to prevent. Rejected. The cost is one extra round trip per page.
- **Synchronous explain.** It concerns one document, but it rewrites the query, and a fuzzy query's rewrite scans the whole field. Rejected for the same reason.
- **Paging with `searchAfter`** (the last hit's document and score as a cursor). Each page costs the same however deep it is. But a later sort needs the sort values in the cursor (`FieldDoc.fields`: longs, doubles, `BytesRef`s), each typed on the wire, and the TUI could not jump to a page. Offset paging up to 10,000 hits costs little more. Rejected for now. It can come back as an optional `after` without breaking `offset`.
- **A server-side search session** that keeps the hits, as Luke does. It brings ids, lifetimes and invalidation on close (ADR 0007). Rejected.
- **A `mode` enum with one shared set of fields** for the query. The fields would mean different things per mode, and later parsers bring options that don't apply to the others. Rejected for the alternatives above.
- **Luke's `field:term` text for term queries.** It cannot express a term with `:` before the value or a binary term. Rejected.
- **A choice of analyzers now** (standard, whitespace, keyword, simple). It adds `lucene-analysis-common`, and the Analysis ADR would probably replace the enum. Deferred.

## Consequences

- `leia-core` depends on `lucene-queryparser`, which brings `lucene-queries` and `lucene-sandbox` into the server's fat jar.
- The TUI has a fifth screen. It holds a query line, the hits, and a panel with the parsed query or the explanation. The TUI polls a search job, an explain job and a term scan independently, so it now tracks one job per kind instead of only the scan.
- The overview's top terms can open a term query on the search screen, which closes the backlog item "search docs by a top term".
- Without a points-aware parser, a numeric range in classic syntax becomes a term range over text, which matches nothing on point fields. The standard query parser slice fixes this.
