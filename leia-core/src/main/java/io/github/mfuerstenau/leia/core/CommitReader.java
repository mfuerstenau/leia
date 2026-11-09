package io.github.mfuerstenau.leia.core;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import org.jspecify.annotations.Nullable;

/**
 * Reads the commits of an index directory from their {@code segments_N}, {@code .si} and {@code
 * .fnm} files (ADR 0009, ADR 0037). Every call reads the directory again, so it sees commits written
 * after the index was opened. Nothing is written.
 */
final class CommitReader {

    private static final String SEGMENTS_PREFIX = IndexFileNames.SEGMENTS + "_";

    private CommitReader() {}

    static CommitList commits(Directory directory, long currentGeneration) throws IOException {
        List<CommitInfo> commits = new ArrayList<>();
        List<CommitList.Unreadable> unreadable = new ArrayList<>();
        for (String file : directory.listAll()) {
            // Not pending_segments_N, which a writer has not finished.
            if (!file.startsWith(SEGMENTS_PREFIX)) {
                continue;
            }
            try {
                commits.add(info(readCommit(directory, file), currentGeneration));
            } catch (IOException e) {
                unreadable.add(new CommitList.Unreadable(file, e));
            }
        }
        commits.sort(Comparator.comparingLong(CommitInfo::generation).reversed());
        return new CommitList(List.copyOf(commits), List.copyOf(unreadable));
    }

    /** The commit of {@code generation}; empty if the directory has no such {@code segments_N}. */
    static Optional<CommitDetails> commit(Directory directory, long generation, long currentGeneration)
            throws IOException {
        if (generation < 1) {
            return Optional.empty();
        }
        String segmentsFile = IndexFileNames.fileNameFromGeneration(IndexFileNames.SEGMENTS, "", generation);
        if (!Arrays.asList(directory.listAll()).contains(segmentsFile)) {
            return Optional.empty();
        }
        SegmentInfos infos = readCommit(directory, segmentsFile);

        Map<String, String> segmentOfFile = new HashMap<>();
        for (SegmentCommitInfo segment : infos) {
            for (String file : segment.files()) {
                segmentOfFile.put(file, segment.info.name);
            }
        }
        List<CommitDetails.CommitFile> files = new ArrayList<>();
        for (String file : infos.files(true)) {
            files.add(new CommitDetails.CommitFile(file, segmentOfFile.get(file), size(directory, file)));
        }
        files.sort(Comparator.comparing(CommitDetails.CommitFile::name));

        List<CommitDetails.Segment> segments = new ArrayList<>();
        int docBase = 0;
        for (SegmentCommitInfo segment : infos) {
            segments.add(segment(directory, segment, docBase, files));
            docBase += segment.info.maxDoc();
        }
        return Optional.of(
                new CommitDetails(info(infos, currentGeneration), List.copyOf(segments), List.copyOf(files)));
    }

    /**
     * @throws MissingCodecException if a segment's codec isn't loaded (ADR 0018)
     */
    private static SegmentInfos readCommit(Directory directory, String segmentsFile) throws IOException {
        try {
            return SegmentInfos.readCommit(directory, segmentsFile);
        } catch (IllegalArgumentException e) {
            throw MissingCodecException.of(e).orElseThrow(() -> e);
        }
    }

    private static CommitInfo info(SegmentInfos infos, long currentGeneration) {
        int delCount = 0;
        for (SegmentCommitInfo segment : infos) {
            delCount += segment.getDelCount();
        }
        return new CommitInfo(
                infos.getGeneration(),
                infos.getSegmentsFileName(),
                infos.getGeneration() == currentGeneration,
                infos.getVersion(),
                infos.size(),
                infos.totalMaxDoc(),
                delCount,
                infos.getCommitLuceneVersion(),
                new TreeMap<>(infos.getUserData()));
    }

    private static CommitDetails.Segment segment(
            Directory directory, SegmentCommitInfo segment, int docBase, List<CommitDetails.CommitFile> files) {
        SegmentInfo info = segment.info;
        List<CommitDetails.FieldFormats> fieldFormats = List.of();
        IOException fieldInfosError = null;
        try {
            fieldFormats = fieldFormats(directory, segment);
        } catch (IOException e) {
            fieldInfosError = e;
        }
        return new CommitDetails.Segment(
                info.name,
                StringHelper.idToString(info.getId()),
                info.maxDoc(),
                docBase,
                segment.getDelCount(),
                segment.getSoftDelCount(),
                generation(segment.getDelGen()),
                generation(segment.getFieldInfosGen()),
                generation(segment.getDocValuesGen()),
                info.getVersion(),
                info.getMinVersion(),
                info.getCodec().getName(),
                formats(info.getCodec()),
                fieldFormats,
                fieldInfosError,
                info.getUseCompoundFile(),
                info.getHasBlocks(),
                info.getIndexSort() != null ? info.getIndexSort().toString() : null,
                segmentSize(info.name, files),
                new TreeMap<>(info.getDiagnostics()),
                new TreeMap<>(info.getAttributes()));
    }

    /**
     * The formats of each field, sorted by name, from the segment's field infos, read as {@code
     * SegmentReader} reads them: the latest update if there is one, otherwise the segment's own,
     * which may be in its compound file (ADR 0037). Only names are read, so a format that isn't
     * loaded is no error.
     */
    private static List<CommitDetails.FieldFormats> fieldFormats(Directory directory, SegmentCommitInfo segment)
            throws IOException {
        SegmentInfo info = segment.info;
        Codec codec = info.getCodec();
        FieldInfos fieldInfos;
        if (segment.hasFieldUpdates()) {
            String suffix = Long.toString(segment.getFieldInfosGen(), Character.MAX_RADIX);
            fieldInfos = codec.fieldInfosFormat().read(directory, info, suffix, IOContext.READONCE);
        } else if (info.getUseCompoundFile()) {
            try (Directory compound = codec.compoundFormat().getCompoundReader(directory, info)) {
                fieldInfos = codec.fieldInfosFormat().read(compound, info, "", IOContext.READONCE);
            }
        } else {
            fieldInfos = codec.fieldInfosFormat().read(directory, info, "", IOContext.READONCE);
        }
        List<CommitDetails.FieldFormats> formats = new ArrayList<>();
        for (FieldInfo field : fieldInfos) {
            formats.add(new CommitDetails.FieldFormats(
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
                            field.hasVectorValues(),
                            PerFieldKnnVectorsFormat.PER_FIELD_FORMAT_KEY,
                            codec.knnVectorsFormat())));
        }
        formats.sort(Comparator.comparing(CommitDetails.FieldFormats::field));
        return List.copyOf(formats);
    }

    /** The per-field attribute {@code key} if the field has the format, otherwise the codec's. */
    private static @Nullable String format(FieldInfo field, boolean has, String key, Object codecFormat) {
        if (!has) {
            return null;
        }
        String attribute = field.getAttribute(key);
        return attribute != null ? attribute : formatName(codecFormat);
    }

    /** Lucene's generations are -1 when there is none. */
    private static @Nullable Long generation(long generation) {
        return generation < 0 ? null : Long.valueOf(generation);
    }

    private static @Nullable Long size(Directory directory, String file) throws IOException {
        try {
            return directory.fileLength(file);
        } catch (NoSuchFileException | FileNotFoundException _) {
            return null;
        }
    }

    private static @Nullable Long segmentSize(String segment, List<CommitDetails.CommitFile> files) {
        long sum = 0;
        for (CommitDetails.CommitFile file : files) {
            if (segment.equals(file.segment())) {
                Long size = file.size();
                if (size == null) {
                    return null;
                }
                sum += size;
            }
        }
        return sum;
    }

    private static CommitDetails.CodecFormats formats(Codec codec) {
        return new CommitDetails.CodecFormats(
                formatName(codec.postingsFormat()),
                formatName(codec.docValuesFormat()),
                formatName(codec.storedFieldsFormat()),
                formatName(codec.termVectorsFormat()),
                formatName(codec.normsFormat()),
                formatName(codec.pointsFormat()),
                formatName(codec.knnVectorsFormat()),
                formatName(codec.fieldInfosFormat()),
                formatName(codec.segmentInfoFormat()),
                formatName(codec.liveDocsFormat()),
                formatName(codec.compoundFormat()));
    }

    /**
     * The SPI name of a named format; otherwise the simple class name, or for an anonymous class
     * that of the class it extends, such as {@code Lucene49NormsFormat} for 4.10's norms.
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
}
