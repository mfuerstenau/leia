package io.github.mfuerstenau.leia.lucene3;

import io.github.mfuerstenau.leia.runtime.Commits;
import io.github.mfuerstenau.leia.runtime.DirectoryKind;
import io.github.mfuerstenau.leia.runtime.IndexReadException;
import io.github.mfuerstenau.leia.runtime.RuntimeIndex;
import io.github.mfuerstenau.leia.runtime.support.Failures;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.TreeMap;
import org.apache.lucene.index.IndexFileNames;
import org.apache.lucene.index.SegmentInfo;
import org.apache.lucene.index.SegmentInfos;
import org.apache.lucene.store.Directory;

/**
 * The commits of a directory of Lucene 2.x or 3.x, read with Lucene 3.6 (ADR 0045): each {@code
 * segments_N} on its own, through a directory without locks, opened anew for each call.
 */
final class Lucene3Commits implements Commits {

    private static final String SEGMENTS_PREFIX = IndexFileNames.SEGMENTS + "_";

    @Override
    public CommitList commits(Path dir, DirectoryKind kind) throws IOException {
        try (Directory directory = Directories.open(dir, kind)) {
            List<String> names = new ArrayList<>();
            for (String name : directory.listAll()) {
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
                    commits.add(commit(read(directory, name)));
                } catch (IOException | RuntimeException e) {
                    unreadable.add(new Unreadable(name, Failures.unreadableCommit(e)));
                }
            }
            return new CommitList(List.copyOf(commits), List.copyOf(unreadable));
        }
    }

    @Override
    public Optional<CommitDetails> commit(Path dir, DirectoryKind kind, long generation) throws IOException {
        try {
            return readCommit(dir, kind, generation);
        } catch (IOException | RuntimeException e) {
            Optional<IndexReadException> failure = Lucene3Failures.classify(e);
            if (failure.isPresent()) {
                throw failure.get();
            }
            throw e;
        }
    }

    private static Optional<CommitDetails> readCommit(Path dir, DirectoryKind kind, long generation)
            throws IOException {
        try (Directory directory = Directories.open(dir, kind)) {
            String name = IndexFileNames.fileNameFromGeneration(IndexFileNames.SEGMENTS, "", generation);
            if (generation <= 0 || !directory.fileExists(name)) {
                return Optional.empty();
            }
            SegmentInfos infos = read(directory, name);
            List<Segment> segments = new ArrayList<>();
            Map<String, CommitFile> files = new TreeMap<>();
            files.put(name, new CommitFile(name, Optional.empty(), size(directory, name)));
            int docBase = 0;
            for (SegmentInfo info : infos) {
                segments.add(segment(info, docBase));
                docBase += info.docCount;
                for (String file : info.files()) {
                    // A doc store shared by several segments belongs to the first.
                    files.putIfAbsent(file, new CommitFile(file, Optional.of(info.name), size(directory, file)));
                }
            }
            return Optional.of(new CommitDetails(commit(infos), List.copyOf(segments), List.copyOf(files.values())));
        }
    }

    private static SegmentInfos read(Directory directory, String segmentsFile) throws IOException {
        SegmentInfos infos = new SegmentInfos();
        infos.read(directory, segmentsFile);
        return infos;
    }

    private static Commit commit(SegmentInfos infos) throws IOException {
        int maxDoc = 0;
        int delCount = 0;
        for (SegmentInfo info : infos) {
            maxDoc += info.docCount;
            delCount += info.getDelCount();
        }
        return new Commit(
                infos.getGeneration(),
                infos.getSegmentsFileName(),
                infos.getVersion(),
                infos.size(),
                maxDoc,
                delCount,
                newestVersion(infos),
                new TreeMap<>(infos.getUserData()));
    }

    /** Lucene 3 doesn't record which version wrote a commit; the newest segment stands in for it. */
    private static String newestVersion(SegmentInfos infos) {
        String newest = "2.x";
        for (SegmentInfo info : infos) {
            String version = version(info);
            if (RuntimeIndex.VERSION_ORDER.compare(version, newest) > 0) {
                newest = version;
            }
        }
        return newest;
    }

    /** The version that wrote {@code info}, such as {@code 3.6.2}; {@code 2.x} before 3.0, which didn't record it. */
    static String version(SegmentInfo info) {
        return info.getVersion() == null ? "2.x" : info.getVersion();
    }

    private static Segment segment(SegmentInfo info, int docBase) throws IOException {
        Map<String, String> attributes = new TreeMap<>();
        attributes.put("docStoreSegment", String.valueOf(info.getDocStoreSegment()));
        attributes.put("docStoreOffset", Integer.toString(info.getDocStoreOffset()));
        attributes.put("docStoreIsCompoundFile", Boolean.toString(info.getDocStoreIsCompoundFile()));
        attributes.put("hasProx", Boolean.toString(info.getHasProx()));
        attributes.put("hasVectors", Boolean.toString(info.getHasVectors()));
        OptionalLong size;
        try {
            size = OptionalLong.of(info.sizeInBytes(true));
        } catch (IOException _) {
            // A file that can't be stat'ed (ADR 0009 §5).
            size = OptionalLong.empty();
        }
        Map<String, String> diagnostics = info.getDiagnostics();
        return new Segment(
                info.name,
                info.docCount,
                docBase,
                info.getDelCount(),
                delGen(info),
                version(info),
                info.getUseCompoundFile(),
                size,
                diagnostics == null ? Map.of() : new TreeMap<>(diagnostics),
                attributes);
    }

    /**
     * The generation of the deletions file, from its name: {@code _0_1.del} is generation 1, and
     * {@code _0.del}, from before 2.1, generation 0.
     */
    private static OptionalLong delGen(SegmentInfo info) {
        String name = info.getDelFileName();
        if (name == null) {
            return OptionalLong.empty();
        }
        String rest = name.substring(info.name.length(), name.length() - ".del".length());
        return OptionalLong.of(rest.startsWith("_") ? Long.parseLong(rest.substring(1), Character.MAX_RADIX) : 0);
    }

    private static OptionalLong size(Directory directory, String file) {
        try {
            return OptionalLong.of(directory.fileLength(file));
        } catch (IOException _) {
            return OptionalLong.empty();
        }
    }
}
