# ADR 0054 — The back stack keeps what a jump left of the search and commits screens

- **Status:** Accepted
- **Date:** 2026-10-13
- **Amends:** ADR 0053 (Decision §1, §4, and Consequences)

## Context

ADR 0053 gave back-stack entries marks for the terms and documents screens only. The search and commits screens still came back in their latest state: a search hit → document → `m` → `⌫ ⌫` showed the More Like This hits, not the search the hit came from, and between the two `⌫` the document's `[`/`]` (`Walk.SEARCH_HITS`) stepped through those hits. A segment → document → `C` on a document of another segment → `⌫ ⌫` showed the other segment.

## Decision

1. **Two more marks.** `Back` is `Back(screen, terms, documents, search, commits)`, each mark optional. A jump pushes:
   - the search mark when it leaves the search screen, and also when it leaves the documents screen while the document steps through search hits, as the terms mark travels with a term-docs document;
   - the commits mark when it leaves the commits screen.
2. **`Search.Mark`** holds what a jump into the screen (`termQuery`, `moreLikeThis`, `docValuesQuery`, `knnQuery`) or the next search replaces: the mode, the field, the query line, the doc values and vector field with their encoding, the line's error; the submitted search with its hits, the selection, the details' scroll and the focus; the explanation, the highlight and the regex scan. The objects are references, as in ADR 0053. The settings (parser, similarity and sort, More-Like-This settings, fields per hit, highlights, exact count, k, exactness and the KNN filter) are not marked: they are the user's choices, and no jump changes them.
3. **`Search.restore`** changes nothing if the screen still shows the mark's search (the same submitted search, scan and mode), so that a document's `]` steps made since stay. Otherwise it puts the marked state back, closes the line, and returns `Cancel` with the jobs of the newer search if any run: the one exception to "restore sends nothing", and still no fetch. What the mark left running was stopped by the newer search, and shows so as `Esc` would have: a first page that never came says `Cancelled.`, as do an explanation or a highlight; a scan shows as stopped. A next page that never came is fetched again on scroll.
4. **`Commits.Mark`** holds the selection only: the index directory, commit and segment rows, the focus, whether the details show the segment, and their scroll. The fetched commits are caches a jump doesn't replace. `restore` sets the selection back and forgets a segment that `showSegment` still waited for; it sends nothing, and what is missing comes from `initialRequest()`.
5. **`Backspace`** restores the terms mark, then the commits mark, then the search mark, then the documents mark. It returns the document's fetch, with the newer search's jobs added to its `cancel` if both are due; else the search's `Cancel`; else the screen's `initialRequest()`.
6. A reopen drops all four marks (ADR 0053 §5).

## Consequences

- Going back along search → document → More Like This, or commits → document → commits, shows each screen as the jump left it.
- Restoring a search can show `Cancelled.` for a page, explanation or highlight that was still running when the next search replaced it; the user searches again with `⏎`.
