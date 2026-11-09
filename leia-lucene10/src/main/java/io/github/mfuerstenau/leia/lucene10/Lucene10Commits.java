package io.github.mfuerstenau.leia.lucene10;

import io.github.mfuerstenau.leia.core.CommitInfo;
import io.github.mfuerstenau.leia.core.DirectoryOnlyIndex;
import io.github.mfuerstenau.leia.core.IndexOpenException;
import io.github.mfuerstenau.leia.runtime.Commits;
import io.github.mfuerstenau.leia.runtime.DirectoryKind;
import io.github.mfuerstenau.leia.runtime.support.Failures;
import io.github.mfuerstenau.leia.runtime.support.Optionals;
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
                    list.unreadable().stream().map(Lucene10Commits::unreadable).toList());
        }
    }

    /** A missing file by its name alone, as the old runtimes say it; leia-core names its whole path. */
    private static Unreadable unreadable(io.github.mfuerstenau.leia.core.CommitList.Unreadable unreadable) {
        String message = Failures.missingFile(unreadable.error()).isPresent()
                ? Failures.unreadableCommit(unreadable.error())
                : unreadable.message();
        return new Unreadable(unreadable.segmentsFile(), message);
    }

    @Override
    public Optional<CommitDetails> commit(Path dir, DirectoryKind kind, long generation) throws IOException {
        try (DirectoryOnlyIndex index = open(dir, kind)) {
            Optional<io.github.mfuerstenau.leia.core.CommitDetails> read;
            try {
                read = index.commit(0, generation);
            } catch (IOException e) {
                throw Lucene10Failures.reading(e);
            }
            return read.map(details -> new CommitDetails(
                    commit(details.commit()),
                    details.segments().stream().map(Lucene10Commits::segment).toList(),
                    details.files().stream()
                            .map(file -> new CommitFile(
                                    file.name(), file.segment().orElse(null), Optionals.orNull(file.size())))
                            .toList()));
        }
    }

    private static DirectoryOnlyIndex open(Path dir, DirectoryKind kind) throws IOException {
        try {
            return DirectoryOnlyIndex.open(dir, Lucene10Index.directoryImpl(kind));
        } catch (IndexOpenException e) {
            throw Lucene10Failures.opening(e);
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
                Optionals.orNull(segment.delGen()),
                segment.version().toString(),
                segment.compound(),
                Optionals.orNull(segment.sizeInBytes()),
                segment.diagnostics(),
                segment.attributes(),
                segment.id(),
                segment.codec(),
                new Formats(
                        formats.postings(),
                        formats.docValues(),
                        formats.storedFields(),
                        formats.termVectors(),
                        formats.norms(),
                        // Lucene 10 always has them.
                        formats.points(),
                        formats.knnVectors(),
                        formats.fieldInfos(),
                        formats.segmentInfo(),
                        formats.liveDocs(),
                        formats.compound()),
                segment.fieldFormats().stream()
                        .map(field -> new FieldFormats(
                                field.field(),
                                field.postings().orElse(null),
                                field.docValues().orElse(null),
                                field.knnVectors().orElse(null)))
                        .toList(),
                segment.fieldInfosMessage().orElse(null),
                Optionals.orNull(segment.fieldInfosGen()),
                Optionals.orNull(segment.docValuesGen()),
                segment.softDelCount(),
                segment.minVersion().map(Version::toString).orElse(null),
                segment.indexSort().orElse(null),
                segment.hasBlocks());
    }
}
