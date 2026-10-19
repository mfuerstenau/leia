# ADR 0039 — Analyzers per field and auto stop words

- **Status:** Accepted
- **Date:** 2026-10-11
- **Amends:** ADR 0014 §3 (`analyze` gains `field`), ADR 0023 (the last two analyzers it deferred)
- **Amended by:** ADR 0061 (auto stop words on an index that a runtime reads)

## Context

ADR 0023 deferred `PerFieldAnalyzerWrapper` and `QueryAutoStopWordAnalyzer`. ADR 0038 added the other two. Both of these depend on the field name, and leia's analysis didn't:

- `analyze` ran `tokenStream("", text)`, and the parsers and More-Like-This got one analyzer for every field.
- **`PerFieldAnalyzerWrapper(defaultAnalyzer, Map<String, Analyzer>)`** gives each field its own analyzer. Applications use it all the time, for example for a keyword field beside text fields, or a field of collation keys (ADR 0038), whose ranges only work with that field's analyzer. Lucene's wrapper doesn't close the analyzers it wraps.
- **`QueryAutoStopWordAnalyzer(delegate, IndexReader, fields, maxDocFreq | maxPercentDocs)`** scans the terms of the fields when it is built. A term in more than `maxDocFreq` documents is a stop word of its field, and `maxPercentDocs` stands for `(int) (numDocs * maxPercentDocs)`, Lucene's default being 0.4. Without fields, it uses every indexed field (`FieldInfos.getIndexedFields`). `docFreq` counts deleted documents. It then wraps each such field's stream in a `StopFilter` with its words. There is no constructor that takes the words.
- The scan reads every term of the fields, which can take long, so it must be a job (hard rule 7).
- The TUI's analyzer is data in every request (ADR 0014 §1), so the words would travel with it.

## Decision

1. **`Analyzer` gains `fields` and `autoStopWords`,** both lists, empty for none. The analyzer's own alternative, with its wrappers, is the default. The layers are `AutoStopWords(PerField(default = LimitTokenCount(Shingles(own)), field → its analyzer))`.
   - `FieldAnalyzer {field, analyzer}`. A field analyzer is an `Analyzer` without `fields` or `autoStopWords` of its own: nesting is one level only. Each field is named once, and not empty.
   - `FieldStopWords {field, words}`, each field named once. The words are case-sensitive, as `QueryAutoStopWordAnalyzer` builds its `CharArraySet`.
   - Breaking a rule is `INVALID_PARAMS`, and a field analyzer that can't be built is `INVALID_ANALYZER`, with the field in the message.
2. **The core builds its own wrappers.**
   - `PerFieldAnalyzer` does what `PerFieldAnalyzerWrapper` does.
   - `AutoStopWordsAnalyzer` does what `QueryAutoStopWordAnalyzer.wrapComponents` does: a `StopFilter` with the field's words, and only for the fields that have them.
   - Both also close what they wrap (ADR 0023 §7).
3. **`analyze` gains `field?`.** The text is analyzed with that field's analyzer and with the field's name, so `tokenStream` and the analyzers that look at the name see it. Without it, the default analyzer and `""` are used, as before (ADR 0014 §3).
   - A field with its own analyzer shows that analyzer's stages.
   - A field with stop words adds one stage, `AutoStopWords`, after the others.
   - The parsers and More-Like-This need no change: they already build the whole `Analyzer`, and Lucene asks it per field.
4. **`startAutoStopWords {fields, maxDocFreq?, maxPercentDocs?}` is a job,** polled with `getAutoStopWords` and stopped with `cancelJob`.
   - Exactly one threshold is given: `maxDocFreq` at least 0, or `maxPercentDocs` from 0 to 1, Lucene's fraction. Otherwise the call fails with `INVALID_PARAMS`.
   - Empty `fields` means every indexed field. A field that is missing or not indexed is `FIELD_NOT_FOUND`. It needs a reader (`NO_READER`).
   - The result is `GetAutoStopWordsResult {termsScanned, stopWords?}`, and `AutoStopWords {maxDocFreq, fields}` carries the threshold that was used. Each scanned field is listed, sorted by name, with its words in term order, and a field without words is listed too.
   - The loop is Lucene's: `MultiTerms.getTerms`, then `docFreq > maxDocFreq`, then `utf8ToString`. Progress and cancellation go through `ScanProgress`.
   - *Amended by ADR 0061 §2, §3:* it works on an index that a runtime reads too. The loop is `AutoStopWords.find`, over the terms of `Terms.terms` there, and a search there takes the words through the runtime's `AnalyzerSpec.AutoStopWords`.
5. **At most `MAX_WORDS` = 10,000 words per field.** With more, the job fails with `INVALID_PARAMS` and asks for a higher threshold. A partial list would mislead, and every later request would carry it.
6. **The TUI holds the analysis** (`AnalysisSettings`): the default analyzer, the field analyzers and the stop words.
   - On the analysis screen, `f`/`F` pick the field that the text is analyzed for, out of the open index's indexed fields, or the default.
   - `b` builds the shown field's analyzer. A field's first analyzer starts as a copy of the default one. `D` drops it again.
   - `w` opens a popup that finds the stop words. It has the threshold as a percentage or a document frequency, and the fields, none for all. It shows the progress, Esc stops the job, and once the job is done it shows how many words each field has. The words can be listed (`v`) and dropped (`d`).
   - The search screen parses and likes with all of it, and its info line has a summary such as `EnglishAnalyzer · 2 fields own · 14 auto stop words in 2 fields`.
7. **The stop words belong to their index.** Opening another index, or closing it, drops them. A reopen keeps them, and the summary then says that they were found before it. The field analyzers are kept, as the analyzer always was.

## Alternatives considered

- **Lucene's `PerFieldAnalyzerWrapper` itself.** It is final and doesn't close the analyzers it wraps. Leia's wrapper does the same and closes them.
- **The server builds `QueryAutoStopWordAnalyzer` from the open index on each request.** It would scan the terms on the backend thread for every analysis, search and More-Like-This, which hard rule 7 forbids. It would also tie the analyzer to the index, unlike every other part of `Analyzer` (ADR 0014 §1). Rejected; the job finds the words once.
- **The server keeps the words and gives them an id.** That is server state, which the TUI would have to track across reopens. The words are small enough to send: at most 10,000 per field. Rejected.
- **Field analyzers that nest further, or carry stop words of their own.** Lucene allows it, but nobody needs a per-field analyzer inside a per-field analyzer. One level keeps the records and the TUI flat. Rejected.
- **A partial list above the limit.** It would look like a complete one. Rejected for the failure.

## Consequences

- Protocol:
  - `Analyzer` gains `fields` and `autoStopWords`, and `AnalyzeParams` gains `field`.
  - New records `FieldAnalyzer`, `FieldStopWords`, `AutoStopWords`, `StartAutoStopWordsParams` and `GetAutoStopWordsResult`, and the methods `startAutoStopWords` and `getAutoStopWords`.
- Core:
  - `AnalyzerSpec` gains `PerField` and `AutoStopWords`.
  - New `PerFieldAnalyzer` and `AutoStopWordsAnalyzer`.
  - `Analyzers.analyze` takes the field.
  - `ReadOnlyIndex.autoStopWords` does the scan. A test checks that its words are those of Lucene's `QueryAutoStopWordAnalyzer` on the same reader.
- Server: `AnalyzerSpecs` maps the new fields and checks them, and the new handlers `StartAutoStopWordsHandler` and `GetAutoStopWordsHandler`.
- TUI:
  - `AnalysisSettings` replaces `AnalyzerSettings` as what the screens share.
  - The analysis screen gets its field line and `D`, and the new `AutoStopWordsForm` with its job (`Poll.AutoStopWords`).
- The demo index gains `title_key`, the title as German collation keys at primary strength. On it, a classic `title_key:[a TO m]` with a collation analyzer for that field finds 206 documents (`DemoIndexTest`).
