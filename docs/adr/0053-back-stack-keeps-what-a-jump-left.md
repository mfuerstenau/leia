# ADR 0053 — The back stack keeps what a jump left

- **Status:** Accepted
- **Date:** 2026-10-13
- **Amends:** ADR 0052 (§2 The back stack, and Consequences)
- **Amended by:** ADR 0054 (marks for the search and commits screens)

## Context

Under ADR 0052 §2 an entry of the back stack is only a `Screen`, and `Backspace` shows that screen object as it is now. When a later jump changed the same screen, going back showed the changed state: terms on `id`/`1`, `⏎` on the posting, `T` on `body`, `⌫`, `⌫` ended on the terms of `body`, not `id`/`1`. Worse, between the two `⌫` the document's `[`/`]` (`Walk.TERM_DOCS`) stepped through the terms screen's live postings, which were `body`'s by then.

## Decision

1. **An entry keeps marks.** App's back stack holds `Back(screen, Optional<Terms.Mark>, Optional<Documents.Mark>)`. A jump pushes:
   - the documents mark when it leaves the documents screen;
   - the terms mark when it leaves the terms screen, and also when it leaves the documents screen while the document steps through term docs: going back to the document puts back the postings that its `[`/`]` go through.
2. **`Terms.Mark`** holds the field row, the focus, and the screen's own `TermList`, `PostingList` and point tree, with the first page and tree asked for last. They are references, not copies: a move made in them afterwards, such as a document's `]` stepping the postings or a page appended later, still shows, as ADR 0052 has it for keys pressed before a jump. A later jump replaces those objects rather than changing them, so the mark keeps the old ones. `restore` sets them back, clears the seek and the note, and sends nothing; what is still missing (a page that was loading when the screen was left, postings under a filter changed since) comes from `initialRequest()`.
3. **`Documents.Mark`** holds the document, the term hit or the search hits, the pinned segment, the selected field, and the matches if they were found (running ones are cancelled by the next jump). `restore` does nothing if the screen still shows that document, hit, walk and pin; otherwise it sets them back and returns the document to fetch, which shows the hit's positions again through the usual load.
4. **`Backspace`** pops as before; for the entry it shows, it restores the terms mark, then the documents mark, and returns the document's fetch, or else the screen's `initialRequest()`.
5. **A reopen drops the marks** but keeps the stack: they hold the old reader's terms, postings and document ids. After a reopen, `Backspace` shows the latest state, as under ADR 0052.

## Consequences

- Going back along terms → document → terms shows each as the jump left it, without a fetch where the objects are still there.
- The search and commits screens still show their latest state: after a search hit → document → `m` → `⌫ ⌫` the search screen shows the More Like This hits, and after a segment → document → `C` in another segment → `⌫ ⌫` the commits screen shows the other segment. Both are logged as bugs; ADR 0054 fixes them with marks of their own.
