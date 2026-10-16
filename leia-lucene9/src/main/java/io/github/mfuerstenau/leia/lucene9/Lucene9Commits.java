package io.github.mfuerstenau.leia.lucene9;

import io.github.mfuerstenau.leia.runtime.Commits;
import io.github.mfuerstenau.leia.runtime.DirectoryKind;
import io.github.mfuerstenau.leia.runtime.RuntimeIndex;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.TreeMap;
import org.apache.lucene.codecs.Codec;
import org.apache.lucene.codecs.DocValuesFormat;
import org.apache.lucene.codecs.KnnVectorsFormat;
import org.apache.lucene.codecs.PostingsFormat;
import org.apache.lucene.codecs.perfield.PerFieldDocValuesFormat;
import org.apache.lucene.codecs.perfield.PerFieldKnnVectorsFormat;
import org.apache.lucene.codecs.perfield.PerFieldPostingsFormat;
import org.apache.lucene.index.DocValuesType;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.FieldInfos;
import org.apache.lucene.index.IndexFileNames;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.SegmentCommitInfo;
import org.apache.lucene.index.SegmentInfo;
import org.apache.lucene.index.SegmentInfos;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.util.StringHelper;

/**
 * The commits of a directory of an index created by Lucene 8.x, read with Lucene 9.12 (ADR 0049) as leia-core
 * reads those of Lucene 9 and 10 (ADR 0009, ADR 0037): each {@code segments_N} on its own, with its
 * segments' {@code .si} and {@code .fnm} files, through a directory without locks, opened anew for
 * each call.
 */
final class Lucene9Commits implements Commits {

    private static final String SEGMENTS_PREFIX = IndexFileNames.SEGMENTS + "_";

    @Override
    public CommitList commits(Path dir, DirectoryKind kind) throws IOException {
        try (Directory directory = Directories.open(dir, kind)) {
            List<String> names = new ArrayList<>();
            for (String name : directory.listAll()) {
                // Not pending_segments_N, which a writer has not finished.
                if (name.startsWith(SEGMENTS_PREFIX)) {
                    names.add(name);
                }
            }
            names.sort(Comparator.comparingLong(SegmentInfos::generationFromSegmentsFileName)
                    .reversed());
            List<Commit> commits = new ArrayList<>();
            List<Unreadable> unreadable = new ArrayList<>();
            for (String name : names) {
                try {
                    commits.add(commit(SegmentInfos.readCommit(directory, name)));
                } catch (IOException | RuntimeException e) {
                    unreadable.add(new Unreadable(name, Directories.message(e)));
                }
            }
            return new CommitList(List.copyOf(commits), List.copyOf(unreadable));
        }
    }

    @Override
    public Optional<CommitDetails> commit(Path dir, DirectoryKind kind, long generation) throws IOException {
        try (Directory directory = Directories.open(dir, kind)) {
            if (generation <= 0) {
                return Optional.empty();
            }
            String name = IndexFileNames.fileNameFromGeneration(IndexFileNames.SEGMENTS, "", generation);
            if (!List.of(directory.listAll()).contains(name)) {
                return Optional.empty();
            }
            SegmentInfos infos = SegmentInfos.readCommit(directory, name);
            Map<String, String> segmentOfFile = new HashMap<>();
            for (SegmentCommitInfo segment : infos) {
                for (String file : segment.files()) {
                    segmentOfFile.put(file, segment.info.name);
                }
            }
            Map<String, CommitFile> files = new TreeMap<>();
            for (String file : infos.files(true)) {
                files.put(
                        file,
                        new CommitFile(file, Optional.ofNullable(segmentOfFile.get(file)), size(directory, file)));
            }
            List<Segment> segments = new ArrayList<>();
            int docBase = 0;
            for (SegmentCommitInfo segment : infos) {
                segments.add(segment(directory, segment, docBase));
                docBase += segment.info.maxDoc();
            }
            return Optional.of(new CommitDetails(commit(infos), List.copyOf(segments), List.copyOf(files.values())));
        }
    }

    private static Commit commit(SegmentInfos infos) {
        int delCount = 0;
        for (SegmentCommitInfo segment : infos) {
            delCount += segment.getDelCount();
        }
        return new Commit(
                infos.getGeneration(),
                infos.getSegmentsFileName(),
                infos.getVersion(),
                infos.size(),
                infos.totalMaxDoc(),
                delCount,
                commitVersion(infos),
                new TreeMap<>(infos.getUserData()));
    }

    /** The version that wrote the commit; without segments, 9.12 records none, and 8.0.0 stands in for it. */
    private static String commitVersion(SegmentInfos infos) {
        if (infos.getCommitLuceneVersion() != null) {
            return infos.getCommitLuceneVersion().toString();
        }
        String newest = "8.0.0";
        for (SegmentCommitInfo segment : infos) {
            String version = version(segment);
            if (RuntimeIndex.VERSION_ORDER.compare(version, newest) > 0) {
                newest = version;
            }
        }
        return newest;
    }

    /** The version that wrote {@code segment}, such as {@code 8.11.4}. */
    static String version(SegmentCommitInfo segment) {
        return segment.info.getVersion().toString();
    }

    private static Segment segment(Directory directory, SegmentCommitInfo segment, int docBase) {
        SegmentInfo info = segment.info;
        List<FieldFormats> fieldFormats = List.of();
        Optional<String> fieldInfosError = Optional.empty();
        try {
            fieldFormats = fieldFormats(directory, segment);
        } catch (IOException | RuntimeException e) {
            fieldInfosError = Optional.of(Directories.message(e));
        }
        OptionalLong size;
        try {
            size = OptionalLong.of(segment.sizeInBytes());
        } catch (IOException e) {
            // A file that can't be stat'ed (ADR 0009 §5).
            size = OptionalLong.empty();
        }
        Codec codec = info.getCodec();
        return new Segment(
                info.name,
                info.maxDoc(),
                docBase,
                segment.getDelCount(),
                generation(segment.getDelGen()),
                version(segment),
                info.getUseCompoundFile(),
                size,
                new TreeMap<>(info.getDiagnostics()),
                new TreeMap<>(info.getAttributes()),
                Optional.of(StringHelper.idToString(info.getId())),
                Optional.of(codec.getName()),
                Optional.of(formats(codec)),
                fieldFormats,
                fieldInfosError,
                generation(segment.getFieldInfosGen()),
                generation(segment.getDocValuesGen()),
                // Since 7.4.
                segment.getSoftDelCount(),
                // Since 7.0: the oldest version of the segments it was merged from.
                Optional.ofNullable(info.getMinVersion()).map(Object::toString),
                // Since 6.2.
                Optional.ofNullable(info.getIndexSort()).map(Object::toString));
    }

    /**
     * The formats of each field, sorted by name, from the segment's field infos, read as {@code
     * SegmentReader} reads them: the latest update if there is one, otherwise the segment's own,
     * which may be in its compound file. Only names are read, so a format that isn't loaded is no
     * error.
     */
    private static List<FieldFormats> fieldFormats(Directory directory, SegmentCommitInfo segment) throws IOException {
        SegmentInfo info = segment.info;
        Codec codec = info.getCodec();
        FieldInfos fieldInfos;
        if (segment.hasFieldUpdates()) {
            String suffix = Long.toString(segment.getFieldInfosGen(), Character.MAX_RADIX);
            fieldInfos = codec.fieldInfosFormat().read(directory, info, suffix, IOContext.READONCE);
        } else if (info.getUseCompoundFile()) {
            try (Directory compound = codec.compoundFormat().getCompoundReader(directory, info, IOContext.READ)) {
                fieldInfos = codec.fieldInfosFormat().read(compound, info, "", IOContext.READONCE);
            }
        } else {
            fieldInfos = codec.fieldInfosFormat().read(directory, info, "", IOContext.READONCE);
        }
        List<FieldFormats> formats = new ArrayList<>();
        for (FieldInfo field : fieldInfos) {
            formats.add(new FieldFormats(
                    field.name,
                    format(
                            field,
                            field.getIndexOptions() != IndexOptions.NONE,
                            PerFieldPostingsFormat.PER_FIELD_FORMAT_KEY,
                            codec.postingsFormat()),
                    format(
                            field,
                            field.getDocValuesType() != DocValuesType.NONE,
                            PerFieldDocValuesFormat.PER_FIELD_FORMAT_KEY,
                            codec.docValuesFormat()),
                    format(
                            field,
                            field.getVectorDimension() != 0,
                            PerFieldKnnVectorsFormat.PER_FIELD_FORMAT_KEY,
                            codec.knnVectorsFormat())));
        }
        formats.sort(Comparator.comparing(FieldFormats::field));
        return List.copyOf(formats);
    }

    /** The per-field attribute {@code key} if the field has the format, otherwise the codec's. */
    private static Optional<String> format(FieldInfo field, boolean has, String key, Object codecFormat) {
        if (!has) {
            return Optional.empty();
        }
        return Optional.of(Optional.ofNullable(field.getAttribute(key)).orElseGet(() -> formatName(codecFormat)));
    }

    /** No KNN vectors in a segment of 8.x, whose codec has 9.x's placeholder {@code EMPTY} for them. */
    private static Formats formats(Codec codec) {
        return new Formats(
                formatName(codec.postingsFormat()),
                formatName(codec.docValuesFormat()),
                formatName(codec.storedFieldsFormat()),
                formatName(codec.termVectorsFormat()),
                formatName(codec.normsFormat()),
                Optional.of(formatName(codec.pointsFormat())),
                codec.knnVectorsFormat() == KnnVectorsFormat.EMPTY
                        ? Optional.empty()
                        : Optional.of(formatName(codec.knnVectorsFormat())),
                formatName(codec.fieldInfosFormat()),
                formatName(codec.segmentInfoFormat()),
                formatName(codec.liveDocsFormat()),
                formatName(codec.compoundFormat()));
    }

    /**
     * The SPI name of a named format; otherwise the simple class name, or for an anonymous class
     * that of the class it extends.
     */
    private static String formatName(Object format) {
        return switch (format) {
            case PostingsFormat named -> named.getName();
            case DocValuesFormat named -> named.getName();
            case KnnVectorsFormat named -> named.getName();
            default -> {
                Class<?> type = format.getClass();
                while (type.getSimpleName().isEmpty() && type.getSuperclass() != null) {
                    type = type.getSuperclass();
                }
                yield type.getSimpleName();
            }
        };
    }

    /** Lucene's generations are -1 when there is none. */
    private static OptionalLong generation(long generation) {
        return generation < 0 ? OptionalLong.empty() : OptionalLong.of(generation);
    }

    private static OptionalLong size(Directory directory, String file) {
        try {
            return OptionalLong.of(directory.fileLength(file));
        } catch (IOException e) {
            return OptionalLong.empty();
        }
    }
}
