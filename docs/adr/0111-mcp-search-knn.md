# ADR 0111 — KNN in the MCP search tool

- **Status:** Accepted
- **Date:** 2026-10-26
- **Amends:** ADR 0100 (§6, the Tools table: `search`, `index_info`), ADR 0109 (`explain` takes `knn`)

## Context

An agent couldn't ask for the nearest documents to a vector, or to a document's vector. The backend answers already: `SearchQuery.knn` (`KnnQuery`) takes a field, exactly one of `docId` and `vector`, `k` (absent exactly when `exact`), `exact` and a classic-syntax `filter`. The server checks all of it in `KnnChecks` (missing vectors, target count, `k` range, encoding, dimension, finite floats, zeros with cosine), and answers `NOT_SUPPORTED` for a runtime without `Knn` and `FIELD_NOT_FOUND` for an unknown field. Only `leia-mcp` and the docs change.

## Decision

1. **`knn` is a fourth kind of query** beside `query`, `term` and `docValues`: "Give exactly one of query, term, docValues and knn". `search` and `explain` both take it, since both implement `Queries.Arguments` and the server explains a KNN query as any other.
2. **`knn`** is `field`, `vector?` (a list of numbers; whole numbers from -128 to 127 for a BYTE field), `docId?` (a document whose vector is the target, possibly deleted), `k?`, `exact?` and `filter?`. Exactly one of `vector` and `docId` is given; the server checks it.
3. **`k` defaults to `offset + limit` in `search`**, so a page is the k nearest and `nextOffset` is absent unless `k` is given and larger. In `explain` it defaults to `KnnQuery.MAX_K`, so a document matches whenever it is among the nearest the search can find; the description says to pass the search's `k` to explain a hit as ranked. `Queries.query` takes the default as a parameter. `exact` scores every document with a vector, and they all page as hits; `k` doesn't go with it.
4. **`filter`** is an expression in Lucene's classic syntax, parsed with the call's `defaultField` (required with a filter), `analyzer`, `fieldAnalyzers` and `defaultOperator`. `analyzer` and `fieldAnalyzers` go with a filter only. Point ranges don't work in it.
5. **The checks in the tool's own words** are those the server can't make: `k` with `exact`, a BYTE component that is not a whole number from -128 to 127, and the analyzer arguments without a filter. The rest is the server's, so the wording matches the TUI's. The vector's encoding comes from `getIndexStats`; for a field without vectors it is FLOAT32, and the server refuses.
6. **Without `KNN_VECTORS`** among the runtime's features, `knn` fails with `NOT_SUPPORTED` before the backend is called (`ToolError.notSupported`).
7. **`index_info` gains `vectorEncoding?` and `vectorSimilarity?`** per field, present exactly when `vectorDimension` is, so an agent knows whether to send floats or bytes and whether cosine forbids zeros.
8. **Sort and paging carry over.** `sort` works on a KNN query; a hit's `score` is the similarity.
9. In `search`, the bounds of `offset` and `limit` are checked before the query is built, since the default `k` needs them.

## Consequences

- `tools/list` grows by the `knn` schema in `search` and `explain`, about 3.7 KB; `tools.json` pins it.
- A page of a default KNN search can't run past the k nearest; an agent that wants more pages gives a higher `k`, or `exact`.
