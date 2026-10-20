# ADR 0070 — Runtime interface 20: failure reasons and exact hit counts

- **Status:** Accepted
- **Date:** 2026-10-14
- **Amends:** ADR 0049 §4 (the runtime interface); ADR 0044 (how a runtime's failure to read is reported)

## Context

This is step 3a of the Distribution lane (ADR 0065 §4). Step 3 sends every index to a runtime. Before it does, two things that leia-core reports and the runtime interface didn't carry must cross it, or the switch loses them. `Lucene10ParityTest` didn't show them, because none of its calls exercised them:

- **Why an index can't be read.** leia-core tells `CORRUPT_INDEX`, `UNSUPPORTED_INDEX_FORMAT` and `MISSING_CODEC` apart (`OpenIndexHandler.errorCode`, `GetCommitHandler`). A runtime's failure to open, reopen or read a commit was always `INDEX_NOT_READABLE`. After the switch, a missing codec of an index of Lucene 10 would no longer say that a jar could add it (ADR 0018).
- **Exact hit counts.** `startSearch` takes `exactTotalHits`. Without it, leia-core stops counting after a thousand hits, as Luke does, and says so with `GREATER_THAN_OR_EQUAL_TO`. A runtime always counted every hit, and the server always said `EQUAL_TO`.

## Decision

1. **Runtime interface 20.** `LuceneRuntime.API_VERSION` is 20.
2. **`IndexReadException`** (`leia-runtime`), an `IOException` with a `Reason`: `CORRUPT`, `UNSUPPORTED_FORMAT`, `MISSING_CODEC` or `NOT_READABLE`. `LuceneRuntime.open`, `RuntimeIndex.reopen` and `Commits.commits`/`commit` may throw it. A plain `IOException` still means not readable. "No such directory" and "no commit" have no reason: the server finds those before it asks a runtime.
3. **Each runtime classifies its own Lucene's exceptions,** in a small `LuceneNFailures`, a copy per runtime (ADR 0050 §1):
   - `IndexFormatTooOldException`, `IndexFormatTooNewException` → `UNSUPPORTED_FORMAT`;
   - `CorruptIndexException`, `EOFException`, or a missing file in the cause chain → `CORRUPT`;
   - Lucene's `IndexNotFoundException` and anything else → no reason, and the runtime throws the failure as before.

   leia-lucene10 maps leia-core's `IndexOpenException.Reason` for open and reopen, and classifies a commit's failure as `GetCommitHandler` does. 3.6 has no codecs to miss.
4. **`support.Failures`** holds what needs no Lucene (ADR 0050 §2):
   - `missingCodec` reads `NamedSPILoader`'s "An SPI class of type … with name '…' does not exist", whatever package the type is relocated into. One of Lucene's own names (`Lucene99`) is `UNSUPPORTED_FORMAT`, any other `MISSING_CODEC`, both in the words of leia-core's `MissingCodecException`;
   - `missingFile` finds a `NoSuchFileException` or `FileNotFoundException` in the cause chain;
   - `opening` words a failure to open as leia-core does: "Corrupt index: …", "Unsupported index format: …", "Missing codec: …";
   - `named` puts the directory in front, keeping the reason. `IndexParts.open` and the runtimes' `reopen` use it, so the failure of one of several directories keeps its reason.
5. **The messages.** A failure to open or reopen is worded by the runtime, so the server passes it on as it is (`RuntimeResults.readFailure`). A commit's failure carries Lucene's message, and the server words it as `GetCommitHandler` does ("Corrupt commit of generation 3: …"). The codes are leia-core's (`RuntimeResults.errorCode`). A failure without a reason keeps its message, which names the runtime's Lucene.
6. **Exact hit counts.** `Search.search` and `Scoring.search` take `exactTotalHits`, and `Search.SearchResults` has `totalHitsExact`. The old signatures stay as default methods that count every hit, and the old constructors of `SearchResults` stay with `true`, so the tests and callers that don't care don't change (the `Preset` precedent).
   - 9.12 and 10 stop counting after a thousand hits without it, as leia-core does (`Searcher.TOTAL_HITS_THRESHOLD`), with a field sort too.
   - 3.6, 5.5 and 7.7 always count every hit, and say so.
   - The server passes `params.exactTotalHits()` and maps `totalHitsExact` to the relation.

## Alternatives considered

- **A reason on every failure,** `NOT_READABLE` included. Then the server's message for an unreadable index would lose the runtime's Lucene version, which tells the user which Lucene failed. Only leia-lucene10, which has leia-core's words for it, gives `NOT_READABLE`.
- **Classify in the server by the exception's class name.** The names are relocated, and the server would have to know every Lucene's exceptions. The runtime knows its own.
- **One shared classifier in `support`, given the types.** The classification is a dozen lines per runtime; passing Lucene's classes in would be more code than the copy.
- **Change the signatures without defaults.** Every runtime test would change for an argument it doesn't care about.

## Consequences

- `Lucene10ParityTest` compares a search of 3000 matching documents with and without an exact count, a truncated copy of `lucene9` with and without a reader (its `getCommit`), and `custom-codec` without its jar, which the server's `test` now gets. All compare equal, codes and messages.
- Each runtime has a `LuceneNFailuresTest`: the classification of its Lucene's exceptions, the SPI message on 5.5, 7.7 and 9.12, and a truncated copy of its test index, which opens as `CORRUPT`. `Lucene9IndexTest` checks the count without an exact count; `Lucene10FailuresTest` a truncated index and its commit.
- A runtime built against interface 19 is refused, as for every bump.
