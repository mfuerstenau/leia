# ADR 0052 — Cross-screen navigation

- **Status:** Accepted
- **Date:** 2026-10-13
- **Amends:** ADR 0051 (Consequences: the reverse jump from a document to its segment)
- **Amended by:** ADR 0120 (the jumps are in the `g` menu: `g c`, `g t`, `g s`, and the overview's `/` is `g s`; the footer shows `⌫ back` before `? keys`)

## Context

Luke's tabs link to each other (`docs/luke-features.md` §5), and leia had most of those links already:

- Overview top term → terms (`Enter`) or search (`/`)
- Terms posting or point → documents
- Search hit, viewed value or filter → documents
- Documents → analysis (`a`), similar (`m`), nearest (`N`) or search value (`s`)
- Commits segment → documents (ADR 0051)

Four were missing: from a document to its segment on the commits screen (left open by ADR 0051), from the terms screen to a term query (the overview's `/` could, the terms screen couldn't), from a document's field to its terms, and a way back along the jumps.

All of it is in the TUI. No protocol method, handler or runtime changes.

## Decision

1. **New jumps.**

   | Where | Key | Does |
   |---|---|---|
   | Documents | `C` | Opens the commits screen on the opened commit (`Commit.current()`) of the shown document's index directory, with its segment selected in the segments panel and the segment's details shown. Needs `Feature.COMMITS` and the reader's segments. |
   | Documents | `T` | Opens the selected field on the terms screen. If the terms screen opened the document from a posting of that field, at that term with the postings focused (as the overview's `Enter`); otherwise from the field's first term. A field with points and no postings opens its BKD tree. A field with neither says `<field> has no terms` in the document's title until the next key. Needs `Feature.TERMS`. |
   | Terms | `s` | Runs a term query on the search screen: on the postings column for the postings' term, otherwise for the selected term. On a point field, the note `s searches terms only`, as `/` and `e`. Needs `Feature.SEARCH`, as the overview's `/`. |
   | everywhere | `Backspace` | Goes back to the screen that the last jump came from. |

   Documents sends `Request.ShowCommit(index, segment)` and `Request.BrowseTerms(field, term, points)`, Terms `Request.Search(field, term)`. `Commits.showSegment` selects the opened commit, focuses the segments and selects the segment by name, once the list and the commit are in if they aren't yet; a list that comes in meanwhile selects the opened commit rather than keeping an older selection. `Terms.show(field, term, points)` generalizes `browse`.

2. **The back stack.** App keeps the screens the jumps came from, the last one first, at most 16; the oldest is dropped beyond that.
   - Only a jump pushes: every switch of the screen that a screen's request makes (the ones above and the existing ones), and the overview's `Enter` and `/`. It pushes nothing when the screen doesn't change.
   - The number keys `1`–`7` forget the stack: `Backspace` retraces an unbroken chain of jumps only. So do an open, an open without a reader and a close. A reopen keeps it.
   - `Backspace` pops until it finds a screen that exists and that the open index supports, and shows it with its `initialRequest()`, as a number key does. Each screen object keeps its state, so the term, the posting, the hit or the document is shown as it was left.
   - `⌫ back` is in the footer after `1-7 screen` while the stack isn't empty.
   - A screen that is `prompting()` (a seek, the doc id prompt, the viewer, the search line and forms, the analysis forms) gets its keys before App's, so `Backspace` still edits there. Outside prompts, no screen used `Backspace`.

## Consequences

- The legends of the documents and terms screens grow by `T terms`, `C commit` and `s search`; on a narrow terminal the documents legend takes one more line.
- `Backspace` is a global key now; a screen that wants it outside a prompt has to take it before App does.
- A jump back shows a screen as it was left, not as it was when the jump started: a key pressed there before the jump is not undone.
