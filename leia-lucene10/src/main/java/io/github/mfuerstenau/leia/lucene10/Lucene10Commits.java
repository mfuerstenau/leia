package io.github.mfuerstenau.leia.lucene10;

import io.github.mfuerstenau.leia.core.CommitInfo;
import io.github.mfuerstenau.leia.core.DirectoryOnlyIndex;
import io.github.mfuerstenau.leia.core.IndexOpenException;
import io.github.mfuerstenau.leia.runtime.Commits;
import io.github.mfuerstenau.leia.runtime.DirectoryKind;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import org.apache.lucene.util.Version;

/**
 * The commits of an index directory, read by leia-core as it reads them without a reader (ADR 0009,
 * ADR 0065): through a {@link DirectoryOnlyIndex} of the directory alone, opened anew for each call.
 */
final class Lucene10Commits implements Commits {

    @Override
    public CommitList commits(Path dir, DirectoryKind kind) throws IOException {
        try (DirectoryOnlyIndex index = open(dir, kind)) {
            io.github.mfuerstenau.leia.core.CommitList list = index.commits(0);
            return new CommitList(
                    list.commits().stream().map(Lucene10Commits::commit).toList(),
                    list.unreadable().stream()
                            .map(unreadable -> new Unreadable(unreadable.segmentsFile(), unreadable.message()))
                            .toList());
        }
    }

    @Override
    public Optional<CommitDetails> commit(Path dir, DirectoryKind kind, long generation) throws IOException {
        try (DirectoryOnlyIndex index = open(dir, kind)) {
            return index.commit(0, generation)
                    .map(details -> new CommitDetails(
                            commit(details.commit()),
                            details.segments().stream()
                                    .map(Lucene10Commits::segment)
                                    .toList(),
                            details.files().stream()
                                    .map(file -> new CommitFile(file.name(), file.segment(), file.size()))
                                    .toList()));
        }
    }

    private static DirectoryOnlyIndex open(Path dir, DirectoryKind kind) throws IOException {
        try {
            return DirectoryOnlyIndex.open(dir, Lucene10Index.directoryImpl(kind));
        } catch (IndexOpenException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /** A commit without segments records no version; Lucene 10's stands in for it. */
    private static Commit commit(CommitInfo commit) {
        return new Commit(
                commit.generation(),
                commit.segmentsFile(),
                commit.version(),
                commit.segmentCount(),
                commit.maxDoc(),
                commit.delCount(),
                Optional.ofNullable(commit.luceneVersion())
                        .map(Version::toString)
                        .orElse(Version.LATEST.toString()),
                commit.userData());
    }

    private static Segment segment(io.github.mfuerstenau.leia.core.CommitDetails.Segment segment) {
        io.github.mfuerstenau.leia.core.CommitDetails.CodecFormats formats = segment.formats();
        return new Segment(
                segment.name(),
                segment.maxDoc(),
                segment.docBase(),
                segment.delCount(),
                segment.delGen(),
                segment.version().toString(),
                segment.compound(),
                segment.sizeInBytes(),
                segment.diagnostics(),
                segment.attributes(),
                Optional.of(segment.id()),
                Optional.of(segment.codec()),
                Optional.of(new Formats(
                        formats.postings(),
                        formats.docValues(),
                        formats.storedFields(),
                        formats.termVectors(),
                        formats.norms(),
                        // Lucene 10 always has them.
                        Optional.of(formats.points()),
                        Optional.of(formats.knnVectors()),
                        formats.fieldInfos(),
                        formats.segmentInfo(),
                        formats.liveDocs(),
                        formats.compound())),
                segment.fieldFormats().stream()
                        .map(field -> new FieldFormats(
                                field.field(), field.postings(), field.docValues(), field.knnVectors()))
                        .toList(),
                segment.fieldInfosMessage(),
                segment.fieldInfosGen(),
                segment.docValuesGen(),
                segment.softDelCount(),
                segment.minVersion().map(Version::toString),
                segment.indexSort(),
                segment.hasBlocks());
    }
}
