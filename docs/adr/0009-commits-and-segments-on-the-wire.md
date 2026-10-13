# ADR 0009 — Commits and segments on the wire

- **Status:** Accepted
- **Date:** 2026-10-06

## Context

Luke's Commits tab lists the commits of the index directory and, for one commit, its files with their sizes, its segments (name, maxDoc, deletions, delGen, Lucene version, codec, size, compound file) and, for one segment, its diagnostics, attributes or codec formats (`docs/luke-features.md` §4.5). Luke reads each of these with its own call.

For leia this raises several questions:

- **Which commits.** leia opens the latest commit (`openIndex`). A directory only holds older commits if the writer's deletion policy kept them, and another process may add commits after leia opened the index.
- **How many calls.** A commit has tens of segments, rarely thousands, and each segment has a few files and a dozen diagnostics. Luke's one call per detail suits a GUI that shows one detail at a time.
- **Unreadable commits.** An older commit can refer to files that are gone or damaged. The opened commit is always readable.
- **Codec formats.** Luke shows the class name of each format of the codec. The postings, doc values and vector formats are per-field wrappers, often anonymous classes, whose class names say little.
- **Long-running work?** Reading a commit reads `segments_N` and one small `.si` file per segment, and stats each file. Nothing iterates over terms or documents.

## Decision

1. **Two synchronous methods**, both on the open index's directory:
   - `getCommits {}` lists the commits of the directory, newest first: generation, `segments_N` file, version, segment count, maxDoc and deletions summed over the segments, the Lucene version that wrote it, its user data, and whether it is `current`, the commit leia opened.
   - `getCommit {generation}` returns one commit whole: the commit as listed, its segments in commit order, and every file of the commit with its size and segment.
2. **Read from the directory, not from the reader.** Both methods read the directory again on every call (`segments_N` and the `.si` files, no index data), so they also show commits written after the index was opened. `current` marks the commit the reader is on, so the TUI can show that a newer commit exists. Reading files never writes anything (hard rule 1).
3. **A segment carries everything about it** in `getCommit`: name, id, maxDoc, its `docBase` (the first document id of the segment in that commit), hard and soft deletion counts, the deletion, field infos and doc values generations, the Lucene version and minimum version, codec, codec formats, compound file, whether it has blocks, index sort, size, diagnostics and attributes. There is no separate call per detail. The TUI shows the details of the selected segment from what it already has.
4. **Codec formats by name.** For the postings, doc values and KNN vectors formats, which are named SPI services, the format's name (such as `PerField40`); for the other formats, the simple class name (such as `Lucene90StoredFieldsFormat`). Per-field formats are not part of this decision.
5. **Sizes may be missing.** A file that a commit refers to but that cannot be stat'ed has no size, and its segment then has no size either. This is not an error: inspecting a damaged directory is part of the job.
6. **Unreadable commits are skipped** by `getCommits`, with a warning in the server log. `getCommit` for such a commit fails with `UNSUPPORTED_INDEX_FORMAT`, `CORRUPT_INDEX` or `INDEX_NOT_READABLE`, as `openIndex` would. Diagnosing them is for Check Index.
7. **Error code `COMMIT_NOT_FOUND` (-32011):** `getCommit` for a generation whose `segments_N` file is not in the directory. Both methods fail with `NO_INDEX_OPEN` if no index is open.
8. **No jobs.** The cost grows with the number of segments and files, not with the index data, so ADR 0005 does not apply.

## Alternatives considered

- **Luke's call per detail** (files, segments, diagnostics, attributes, codec). Each one reads `segments_N` again, and the TUI would need a request per selection change. One commit is small enough for one response. Rejected.
- **Commits from `DirectoryReader.listCommits`.** It fails as a whole if one commit is damaged, and only exposes `IndexCommit`, which lacks the segment details, so the commit has to be read again anyway. Rejected in favour of reading each `segments_N` file.
- **Commits from the opened reader only.** Simpler, but it hides commits that the directory keeps and that a reader on the latest commit can't reach. Rejected.
- **Per-field postings, doc values and vector formats.** They need the field infos of every segment, which for an older commit means reading the `.fnm` files through the codec. Useful, but a separate step.

## Consequences

- The TUI has a fourth screen with the commits on top and, below, the segments of the selected commit and the details of the selected commit or segment.
- An index written with the default deletion policy has one commit. The demo index keeps all of its commits to show more.
- `docBase` relates a segment to the document ids of the documents and terms screens, but only for the current commit; the documents of another commit are not readable in leia.
