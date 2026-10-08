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
import java.util.OptionalLong;
import java.util.TreeMap;
import org.apache.lucene.codecs.Codec;
import org.apache.lucene.codecs.DocValuesFormat;
import org.apache.lucene.codecs.KnnVectorsFormat;
import org.apache.lucene.codecs.PostingsFormat;
import org.apache.lucene.index.IndexFileNames;
import org.apache.lucene.index.SegmentCommitInfo;
import org.apache.lucene.index.SegmentInfo;
import org.apache.lucene.index.SegmentInfos;
import org.apache.lucene.store.Directory;
import org.apache.lucene.util.StringHelper;

/**
 * Reads the commits of an index directory from their {@code segments_N} and {@code .si} files
 * (ADR 0009). Every call reads the directory again, so it sees commits written after the index was
 * opened. Nothing is written.
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
            files.add(new CommitDetails.CommitFile(
                    file, Optional.ofNullable(segmentOfFile.get(file)), size(directory, file)));
        }
        files.sort(Comparator.comparing(CommitDetails.CommitFile::name));

        List<CommitDetails.Segment> segments = new ArrayList<>();
        int docBase = 0;
        for (SegmentCommitInfo segment : infos) {
            segments.add(segment(segment, docBase, files));
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
            SegmentCommitInfo segment, int docBase, List<CommitDetails.CommitFile> files) {
        SegmentInfo info = segment.info;
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
                Optional.ofNullable(info.getMinVersion()),
                info.getCodec().getName(),
                formats(info.getCodec()),
                info.getUseCompoundFile(),
                info.getHasBlocks(),
                Optional.ofNullable(info.getIndexSort()).map(Object::toString),
                segmentSize(info.name, files),
                new TreeMap<>(info.getDiagnostics()),
                new TreeMap<>(info.getAttributes()));
    }

    /** Lucene's generations are -1 when there is none. */
    private static OptionalLong generation(long generation) {
        return generation < 0 ? OptionalLong.empty() : OptionalLong.of(generation);
    }

    private static OptionalLong size(Directory directory, String file) throws IOException {
        try {
            return OptionalLong.of(directory.fileLength(file));
        } catch (NoSuchFileException | FileNotFoundException _) {
            return OptionalLong.empty();
        }
    }

    private static OptionalLong segmentSize(String segment, List<CommitDetails.CommitFile> files) {
        long sum = 0;
        for (CommitDetails.CommitFile file : files) {
            if (file.segment().filter(segment::equals).isPresent()) {
                if (file.size().isEmpty()) {
                    return OptionalLong.empty();
                }
                sum += file.size().getAsLong();
            }
        }
        return OptionalLong.of(sum);
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
     * The SPI name of a named format; otherwise the simple class name, or the full one for an
     * anonymous class.
     */
    private static String formatName(Object format) {
        return switch (format) {
            case PostingsFormat named -> named.getName();
            case DocValuesFormat named -> named.getName();
            case KnnVectorsFormat named -> named.getName();
            default -> {
                String simpleName = format.getClass().getSimpleName();
                yield simpleName.isEmpty() ? format.getClass().getName() : simpleName;
            }
        };
    }
}
