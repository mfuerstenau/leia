# ADR 0007 — Terms and postings on the wire

- **Status:** Accepted
- **Date:** 2026-10-06

## Context

Luke's Documents tab browses a field's terms (first term, next term, seek to a term) and the postings of the current term (first doc, next doc, with the positions table) (`luke-features.md` §4.2). Luke keeps a `TermsEnum` and a `PostingsEnum` open between calls: the model is a cursor that only moves forward.

For leia this raises several questions:

- **Server state.** A cursor on the server would make the server remember what the TUI looks at. It would need ids, cleanup when the index closes, and a rule for what happens when two views browse at once. Every other method so far is stateless, except jobs (ADR 0005).
- **Long-running work?** ADR 0005 makes anything that can take long a job. A full pass over a field's terms or a term's postings can take long; a seek and one page cannot.
- **Byte strings from the client.** Paging and seeking send a term back to the server. Terms are bytes, and the wire carries them as text or hex (ADR 0006).
- **Deleted documents.** Postings include deleted documents until a merge, as the postings statistics do. Luke doesn't show which ones are deleted.
- **Positions.** A page of postings with every occurrence of the term in every document can be large: a frequent term in long documents has thousands of positions per page.

## Decision

1. **Stateless pages.** Three synchronous methods. Each seeks afresh and reads at most one page:
   - `getTerms {field, from?, limit}` returns the terms of a field in term order, starting at `from` (Lucene's `seekCeil`) or at the first term, each with its `docFreq` and `totalTermFreq`. It also returns `next`, the term after the page, or nothing after the last term.
   - `getPostings {field, term, fromDoc, limit}` returns the documents that contain the term from `fromDoc` on (`advance`), each with its `freq` (absent if frequencies are not indexed) and whether it is `deleted`. It also returns the term's `docFreq` and `totalTermFreq`, and `nextDoc`, the document after the page.
   - `getPositions {docId, field, term}` returns where the term occurs in one document: position, offsets and payload as far as they were indexed, as in term vectors (`TermPosition`). It is empty if positions are not indexed.

   Passing `next` back as `from`, or `nextDoc` back as `fromDoc`, continues with the next page. There is no "previous page": Lucene's terms enum only moves forward, so a client that wants to scroll back keeps the pages it has.
2. **No jobs.** A page costs one seek plus at most `limit` steps, and `limit` is capped at 1000 (`INVALID_PARAMS` otherwise), so each call is quick and ADR 0005 does not apply. A full pass over a field (counting, exporting) stays a job.
3. **Merged view for terms and postings, per segment for positions.** Terms and postings come from `MultiTerms`: document ids are index-wide, and the frequencies sum over segments. Positions are read from the document's own segment, like the rest of a document (ADR 0006).
4. **Terms in params are byte strings** (`BytesValue`): the text, or with `binary: true` the bytes in hex. This is the same encoding as the server's results, so a term from a result goes back unchanged. Hex that doesn't parse is `INVALID_PARAMS`. A seek typed by the user is text.
5. **Deleted documents stay in the postings**, marked `deleted`, as `getDocument` reads them (ADR 0006).
6. **Positions per document, on demand.** `getPostings` carries no positions. The TUI asks `getPositions` for the document it shows. Like `getTermVector` next to `getDocument`, this keeps the size of a page proportional to `limit`.
7. **Error code `TERM_NOT_FOUND` (-32009):** `getPostings` for a term that the field doesn't have, and `getPositions` for a document that doesn't contain the term. `getTerms` on a field without postings returns no terms rather than an error.

## Alternatives considered

- **Luke's server-side cursor** (`firstTerm`, `nextTerm`, `firstTermDoc`, `nextTermDoc`). It is cheaper per step, because nothing is sought again, but it brings server state with ids, lifetimes and invalidation on close. A seek per page costs microseconds. Rejected.
- **Term and postings browsing as jobs.** A job gives progress and cancellation, but a single page needs neither, and two calls per page (start, poll) would double the round trips. Rejected.
- **Positions in every posting of a page.** This saves a call per document shown, but a page grows with the term's frequency, not with `limit`. Rejected. A `withPositions` flag on `getPostings` was rejected too: the result's shape would depend on a param, and the TUI shows the positions of one document at a time anyway.
- **Ordinal-based paging** (`seekExact(long ord)`). The default codec's terms dictionary does not support ordinals, and `MultiTerms` never does. Rejected.

## Consequences

- The TUI has a third screen with three columns: fields, terms and postings. Each column fetches its next page when the selection reaches its last row, and keeps what it has fetched. Enter on a posting opens the document on the documents screen, which then shows the term's positions and steps through the term's documents with `[`/`]`.
- `TermPosition` is now shared by term vectors and postings, in the core and in the protocol.
- Going back before the first term of a page means seeking again. The TUI keeps the loaded terms, so scrolling up within them works.
