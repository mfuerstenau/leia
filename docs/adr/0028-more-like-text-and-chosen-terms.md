# ADR 0028 — More-Like-This: like-text, the chosen terms as data, word lengths and stop words

- **Status:** Accepted
- **Date:** 2026-10-07

## Context

ADR 0013 brought More-Like-This as a query alternative: documents like one document of the index. It deferred four parts:

- **Like-text.** Lucene's `MoreLikeThis` can also take its terms from a text instead of a document. The analyzer for that has been in place since ADR 0014.
- **The chosen terms as data.** `rewrittenQuery` lists the terms MLT chose, and with `boost` their weights. It doesn't say *why* a term was chosen: how often it occurs in the source, in how many documents, and how it scored. Lucene keeps those numbers to itself: `ScoreTerm`, `retrieveTerms` and `createQueue` are private (read in Lucene 10.5.2).
- **Word lengths and stop words**: `setMinWordLen`, `setMaxWordLen` and `setStopWords`. Luke offers none of them.

How Lucene handles them:

- `like(Map<String, Collection<Object>>)` analyzes each value of each listed field with the analyzer. The terms of all fields go into one queue of the best terms, as for a document.
- Lucene's `isNoiseWord` leaves out a term shorter than `minWordLen` or longer than `maxWordLen`, measured with `String.length()` (UTF-16 units), or one that is in the stop-word set. It is called on the term after analysis, or on the term vector's term decoded as UTF-8. For analyzed text, it is checked after a token counts toward the 5000-token limit per value.
- The clauses of the built `BooleanQuery` come weakest first. `IndexSearcher.rewrite` then changes the query: a single clause becomes the clause itself, and a `^1.0` boost goes away.

## Decision

1. **Like-text is part of `moreLikeThis`.** `docId` becomes optional, and a new optional `text` joins it. Exactly one of them is present (`INVALID_PARAMS` otherwise).
   - `text` is at most `MAX_TEXT_LENGTH` = 65,536 bytes of UTF-8 (`INVALID_PARAMS` otherwise).
   - The text goes to Lucene's `like(Map)`, once for each chosen field, so the fields share one queue and Lucene's own rules.
   - An empty text, or one with no term in the chosen fields, gives an empty query and no hits, like a document without text. It is not an error.
   - `DOC_NOT_FOUND` is for a `docId` only.
   - `startExplain` takes the same query and builds it again, which is deterministic (ADR 0013 §7).
   - `query` shows the start of the text: `like(text "first 40 characters…", fields […], …)`, cut between code points, with quotes and backslashes escaped.
2. **Word lengths and stop words are new required fields,** in the same style as the other limits:
   - `minWordLen`: 0 for no limit, Lucene's default.
   - `maxWordLen`: optional, absent for no limit, like `maxDocFreq`. When present, it is at least 1 and at least `minWordLen`.
   - `stopWords`: a list, empty for none.

   Both quirks are Lucene's, and leia keeps them:
   - a stop word is compared exactly with the analyzed term, so with the standard analyzer it has to be lowercase;
   - lengths count UTF-16 units, so a character outside the BMP counts twice.

   `query` lists the word limits and the stop words only when they are set.
3. **`SearchResults` gains `moreLikeThisTerms`,** present only for a `moreLikeThis` query. Each term is `{field, term, tf, docFreq, idf, score}`, best first.
   - Lucene still chooses the terms. leia only explains them. In the search job, the MLT query is built once. `LikeTerms` goes through the clauses of the built `BooleanQuery` (before `rewrite` changes it), and for each term works out, with Lucene's own rules:
     - `tf`, counted per field as `MoreLikeThis` counts it: from the document's term vector (`totalTermFreq`, summed per decoded string), or else from the analyzed stored strings or the text, with the same 5000-token limit per value and the same word-length and stop-word checks;
     - `docFreq`, from the reader, deleted documents included;
     - `idf`, from `ClassicSimilarity.idf(docFreq, docCount)`, with `numDocs` when the field's doc count is unknown;
     - `score = tf × idf`, as a float, as Lucene computes it.
   - The order is the clauses in reverse, so ties keep Lucene's order.
   - A test checks that leia's numbers are Lucene's: with `boost`, each clause's boost is its term's score over the weakest term's.
   - It runs on the job's exitable reader, so it can be cancelled like the build (rule 7). Each document-frequency lookup checks for cancellation, and so does each field and stored value.
   - `term` is a string, not a `BytesValue`: `MoreLikeThis` only handles strings, and a term vector's bytes are decoded as UTF-8.
   - `idf` and `score` are Java floats. The Rust side holds them as `f64`, like a hit's score.
4. **The TUI has a mode for each source.** `m` cycles *classic → standard → term → like document → like text → regex*. In like-text mode the query line holds the text, and there is no source mark. Both MLT modes share the `p` popup, which gets three rows: min word length, max word length and stop words (typed in a line, split on whitespace and commas). Both show the chosen terms as a table under the rewritten query: score, tf, df, field and term.

## Alternatives considered

- **A `moreLikeText` alternative of `SearchQuery`.** It would repeat every MLT setting in a second DTO for one changed field. Rejected.
- **Choosing the terms in leia.** leia could rebuild the whole queue and build the `BooleanQuery` itself, which would also give the terms that were dropped, and why. But then leia, not Lucene, would choose the terms, and a difference to the application's MLT could go unnoticed. Rejected. Showing the dropped candidates on top of Lucene's choice was offered too, and left out: a large document has thousands of them.
- **Reading the numbers back from the rewritten query.** Without `boost` it has no scores, and the rewrite merges a single clause and drops `^1.0`. Rejected.
- **A per-value limit other than 5000 tokens (`maxNumTokensParsed`), `maxDocFreqPct` and `boostFactor`.** Still deferred. Each can come as an optional field.
- **Like-text from a stored value on the documents screen.** Deferred: the query line takes a pasted text.

## Consequences

- `MoreLikeThisQuery` changes in place: `docId` becomes optional, and `text`, `minWordLen`, `maxWordLen` and `stopWords` are new. The TUI and the server ship together (ADR 0004).
- New DTO `LikeTerm`, and `SearchResults.moreLikeThisTerms`. New fixture `startSearch.request-more-like-text.json`. `startSearch.request-more-like-this.json` and `getSearch.response-more-like-this.json` change.
- Core: `MoreLikeDocumentQuery` becomes `MoreLikeQuery`, with a source (a document or a text). The new class `LikeTerms` does the term numbers, and there is a new factory, `Queries.moreLikeText`. `Searcher.search` builds an MLT query before the rewrite, to explain its clauses.
- An MLT search does its source's analysis twice: once in Lucene's build, once in `LikeTerms`. It does at most `maxQueryTerms` more document-frequency lookups.
