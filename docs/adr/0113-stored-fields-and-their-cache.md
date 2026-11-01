# ADR 0113 — Which fields are stored, and a cache of them per segment

- **Status:** Accepted
- **Date:** 2026-10-27
- **Amends:** ADR 0051 (a segment of `getIndexStats` has its id and files in the runtime interface), ADR 0100 (§6, the Tools table: `stored_fields`)
- **Amended by:** ADR 0114 (the regex scan and the pattern filter read through the merge instance and the cache, and a filter of the documents that store a field), ADR 0122 (the cache folder is the first writable candidate, else memory)

## Context

Lucene's `FieldInfos` don't record whether a field is stored: since 4.0 a field's stored values are written per document, each with the field's number, and nothing per field. So leia can't say which fields are stored. `index_info` says so, and the search screen's "Stored fields per hit" offers every field. The only way to know is to read the stored fields of every document.

Reading costs about as much as decompressing all the stored data, and skipping the values (a visitor's `needsField` that answers `NO`) saves little: they are decompressed either way, and only their decoding and copying is skipped. Measured on a segment of 400,000 documents (an `_id`, a `_source` of 40 to 240 words, a `rare` number in every seventh), Lucene 10.5.2, the reader's `storedFields()`:

| Stored fields | Values read | Values skipped | Merge instance, values skipped |
|---|---|---|---|
| `BEST_SPEED` (LZ4), 95 MB | 3.7 s | 3.6 s | 0.35 s |
| `BEST_COMPRESSION` (DEFLATE), 55 MB | 32 s | 32 s | 0.35 s |

The reader's `storedFields()` is made for random access: for each document, it decompresses the document's block again from its start. The codec's merge instance, `StoredFieldsReader.getMergeInstance()`, made for reading a segment in order on one thread, decompresses each block once.

What a segment stores never changes once it is written: a merge writes a new segment, deletes only write a new `.liv` file, and doc values updates don't touch stored fields. So what a scan found for a segment stays right as long as the segment exists. leia is read-only, but other processes may write to the index, as Elasticsearch does: a reopen or a refresh adds segments and drops others.

## Decision

1. **The runtime reports, the server keeps.** Runtime interface 24 (`LuceneRuntime.API_VERSION`): `Documents.storedFields(segment, sink, progress)` passes the segment-local id of every document, deleted ones too, with the field of each of its stored values, in document order; `Documents.deletedDocs(segment)` gives the segment's deleted documents. `segment` is the segment's position among the segments of all directories, in the order of `getIndexStats`. `RuntimeIndex.Segment` gains `id` (hex, as Lucene records it since 5.0; empty before) and `files` (the segment's own files, without its deletions). No Lucene class crosses the interface, and no Lucene structure is in the cache, so it is the same for every runtime; `RoaringDocIdSet` isn't in every Lucene leia reads.
2. **Values are skipped, blocks decompressed once.** Lucene 4 and later: a `StoredFieldVisitor` that notes each field and returns `NO`, through the codec's merge instance where the leaf is a segment's (`CodecReader`), else through the leaf. Lucene 3.6: a `FieldSelector` that notes each field and answers `NO_LOAD`, through the reader; 3.x stores fields uncompressed. The Lucene 9 adapter uses the merge instance from 9.5 on, where `StoredFieldsReader` is a `StoredFields`; a server's 9.0 to 9.4 reads through the leaf (ADR 0091).
3. **Per segment, per field, a bitmap.** For each field with a stored value in at least one document of the segment, deleted ones included, the documents that have one, as a RoaringBitmap (`org.roaringbitmap:RoaringBitmap`, Apache 2.0, no dependencies, in `leia.jar`): a field in every document is one run, a rare one a short array. leia-server builds them from what the runtime reports. The live count of a field is its documents minus those of `deletedDocs`, never cached, since a segment's deletions change.
4. **The key of a segment** is its id with its number of documents. A segment without an id, written before 5.0, is its directory's real path, its name, its number of documents, and the size and modification time of each of its files. Copies of an index share their segments' ids, and their data. A segment without either is read each time, not cached.
5. **Two levels.** In memory, the most recently used segments up to 64 MB, for as long as the backend runs, across opens and reopens. On disk, a file per segment in `stored-fields/` of leia's cache folder: `$XDG_CACHE_HOME/leia` (`~/.cache/leia`), or `%LOCALAPPDATA%\leia\cache` on Windows; `-Dleia.cache.dir` moves it, an empty value turns the disk off. The file is named by the first 128 bits of its key's SHA-256 and holds a magic number with the format's version, the whole key (checked on read), the number of documents, each field's name and bitmap in Roaring's portable format, and a CRC32 of all that at the end. It is written to a temporary file and moved into place, so two leia processes can share the folder. A file that doesn't check is deleted and the segment read again; a file of another key is left to be replaced. A file that is read is touched. The first use in a process deletes the files unused for 30 days and the temporary files older than a day, and every write the least recently used files while the folder holds more than 512 MB. A failure of the disk is logged, never passed on. The files hold field names and document numbers, never values, and never lie in an index's folder (hard rule 1).
6. **A job** (rule 7): `startStoredFieldsScan(ignoreCache)` / `getStoredFieldsScan`, with `cancelJob`. Its progress counts documents, a cached segment's at once. A segment the scan stops in, or fails in, isn't cached. The outcome, `StoredFields`, lists each field with its live and all documents, by name, and how many segments the index has and how many came from the cache. `ignoreCache` reads every segment again, and caches what it reads. Every runtime has it: `Feature.STORED_FIELDS`, with the documents.
7. **MCP:** the tool `stored_fields` (`ignoreCache?`) gives the fields, at most 1000, with `liveDocs` and `allDocs`, and `segments`, `cachedSegments` and `tookMillis`. `index_info` points to it.
8. **TUI:** `f` on the overview starts the scan; `Esc` stops it. The field table's title shows its progress, then how many fields are stored; the column `Stored`, the last, shows each field's live documents with a stored value, `-` for a field without, `?` before the scan; `s` sorts by it too. Once the scan is done, the search screen's "Stored fields per hit" offers only the stored fields.

## Consequences

- A second scan of an index, also after a reopen or a restart, reads only the segments it hasn't seen. A refresh of a live Elasticsearch index costs the scan of its new segments.
- `getIndexStats` carries each segment's id and files, from runtime interface 24 on; every runtime and leia-core report them.
- `leia.jar` gains RoaringBitmap; `NOTICE` lists it, and `gradle/verification-metadata.xml` pins it.
- `startStoredScan` and the pattern filter still read through the reader's `storedFields()`. The merge instance would speed them up as much; that, and skipping the documents the cache says lack the scanned fields, is left for later, as is a filter of the documents that store a field.
  *Amended by ADR 0114:* both read a segment at a time through the merge instance, and pass over the documents the cache says store none of the scanned fields; `startFilter` takes a `storedField`.
