# ADR 0013 — More-Like-This

- **Status:** Accepted
- **Date:** 2026-10-06

## Context

Luke's Search tab can search for documents that are like a given document (`luke-features.md` §4.3). It builds the query with Lucene's `MoreLikeThis` (`lucene-queries`) from a document id, the fields to take terms from, a minimum and maximum document frequency, a minimum term frequency and an analyzer. It then searches with that query as with any other.

`MoreLikeThis.like(docId)` works like this (read in Lucene 10.5.2):

- **Terms.** For each field, it reads the document's term vector. A field without one falls back to the document's stored values: every value with a `stringValue()` is analyzed, up to 5000 tokens. Stored numbers and bytes have no string value, so they give no terms. A field with neither a term vector nor stored text gives nothing. The term vector's terms are turned into Java strings as UTF-8, so a binary term that isn't UTF-8 comes back changed and matches nothing.
- **Scores.** Each term scores `tf × idf`. `tf` is its frequency in the document, and `idf` is `ClassicSimilarity`'s, from the term's document frequency in the field (deleted documents included). This is MLT's own similarity: it picks the terms, and has nothing to do with the similarity that scores the hits (ADR 0012).
- **Filters.** A term is dropped if its `tf` is below the minimum term frequency (Lucene's default is 2), its document frequency is below the minimum document frequency (default 5) or above the maximum (default none), or it occurs in no document.
- **Query.** The best `maxQueryTerms` terms (default 25) become `SHOULD` term clauses of a `BooleanQuery`. The clauses come weakest first. With `boost`, each clause is boosted by its score divided by the weakest term's score (Lucene's code calls it `bestScore`, but its queue hands out the weakest term first), so the weakest term keeps boost 1 and the others get more. More clauses than `IndexSearcher.getMaxClauseCount()` (1024) are dropped without a word.
- **The document itself.** It matches its own query, so a live source document is usually the first hit.
- **Cost.** Building the query reads the document's term vectors or stored values and looks up the document frequency of every term in it. A document with a large text field costs thousands of lookups.

Several things need deciding:

- Whether MLT is a query alternative (ADR 0010) or a method of its own.
- Where the query is built. ADR 0010 parses a query in the handler, so that a syntax error fails the request. MLT has no syntax that could fail, but building it can take long, and long-running work is a job (ADR 0005).
- Which of `MoreLikeThis`'s settings the wire carries.
- What the user sees of the terms that MLT chose.

## Decision

1. **`moreLikeThis` is a fourth alternative of the query:** `{docId, fields, minTermFreq, minDocFreq, maxDocFreq?, maxQueryTerms, boost}`. Exactly one of `classic`, `standard`, `term` and `moreLikeThis` is present (`INVALID_PARAMS` otherwise). It works wherever a query does: `startSearch`, with similarity, sort, paging and the stored fields per hit, and `startExplain`.
2. **The handler checks the params, and the job builds the query.** The handler checks:
   - `docId`: from 0 to maxDoc - 1 (`DOC_NOT_FOUND` otherwise). A deleted document can be the source, since its term vectors and stored values can still be read (ADR 0006). It is then not a hit.
   - `fields`: at least one, without repeats, each known (`FIELD_NOT_FOUND`) and indexed (`INVALID_PARAMS`).
   - The numbers: `minTermFreq`, `minDocFreq` and `maxDocFreq` are 0 or more, and `maxQueryTerms` is from 1 to 1024 (`INVALID_PARAMS`). At 1024, Lucene would start to drop clauses without a word.

   Core gives the handler a Lucene `Query` that holds these settings, and whose `rewrite` builds the `MoreLikeThis` query from the searcher's reader. The searcher rewrites the query inside the job, through the `ExitableDirectoryReader` (ADR 0010). Each document-frequency lookup opens a terms enum, and the enum asks the job whether to go on, so a cancelled build stops at the next term. The search and explain code paths stay as they are.
3. **Terms come from Lucene's rules, with `StandardAnalyzer`.** A field's term vector is used if the document has one. Otherwise, its stored strings are analyzed with `StandardAnalyzer`, as the query parsers do (ADR 0010). Choosing the analyzer comes with the analysis screen.
4. **Lucene's defaults, and the TUI starts from them:** `minTermFreq` 2, `minDocFreq` 5, no `maxDocFreq`, `maxQueryTerms` 25, no `boost`. `maxDocFreq` is optional, and an absent value means no limit. Luke offers the frequencies only. `maxQueryTerms` and `boost` are cheap to add, and they decide what the query looks like.
5. **The built query is what the user sees.** `query` in the results is the MLT query as it is written, with its settings, such as `like(doc 42, fields [body, title], minTermFreq 2, minDocFreq 5, maxQueryTerms 25)`. `rewrittenQuery` is the `BooleanQuery` that MLT built, so it lists the chosen terms, and with `boost` their weights. When no term passes the filters, the query is empty: Lucene rewrites it to `MatchNoDocsQuery`, and nothing matches. That is a valid search with no hits, not an error.
6. **The source document stays in the hits,** as in Luke. Leaving it out would change the query, and its score shows the most that a document can score for these terms. The TUI marks it.
7. **Explain builds the query again.** `startExplain` sends the same `moreLikeThis` as the search. The index doesn't change while it is open (rule 1), and building the query is deterministic, so the explanation is of the query that found the hit.

## Alternatives considered

- **A method of its own** (`startMoreLikeThis`). It would need its own similarity, sort, paging, stored fields and explain, which a query alternative gets from `startSearch` as it is. Rejected.
- **Building the query in the handler**, as the parsers do, and sending a ready `BooleanQuery` to the job. The build reads the document and looks up every term, which can take long on a big document, and it couldn't be cancelled (rule 7). Rejected.
- **Sending the chosen terms back as data**, as a list of `{field, term, tf, docFreq, score}`. That shows more than the rewritten query. But it needs a result type of its own, and `rewrittenQuery` with `boost` already shows the terms and their weights. Deferred.
- **Leaving out the source document** with a `MUST_NOT` clause on its id. Rejected (decision 6).
- **Every setting of `MoreLikeThis`**: word lengths, stop words, `maxNumTokensParsed`, `maxDocFreqPct` and `boostFactor`. Luke offers none of them. Deferred: each can come as an optional field without breaking the others.
- **Like-text, not a document** (`like(field, Reader...)`): it searches for documents like a piece of text. It belongs with the analysis screen, which chooses the analyzer. Deferred.
- **An empty `fields` meaning every indexed field**, as `MoreLikeThis` does with no field names. An explicit list shows in the request which fields were used. Rejected.

## Consequences

- `SearchQuery` gains `moreLikeThis`, and `MoreLikeThisQuery` is a new DTO. New fixtures: `startSearch.request-more-like-this.json` and `getSearch.response-more-like-this.json`.
- `leia-core` depends on `lucene-queries` itself. Until now, it only came in through `lucene-queryparser`.
- The TUI's mode key `m` cycles through classic, standard, term and more like this. In MLT mode, the query line holds the document id, and `p` opens a popup for the fields and limits. On the documents screen, `m` searches for documents like the one shown.
- An MLT search on a document without term vectors or stored text in the chosen fields finds nothing, and the rewritten query says why.
