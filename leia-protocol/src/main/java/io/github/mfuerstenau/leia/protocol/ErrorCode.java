package io.github.mfuerstenau.leia.protocol;

/**
 * Every error code of an expected failure, with the number that the UI shows next to its name. The
 * codes taken from JSON-RPC come first, then leia's own codes from {@code -32000} downward.
 */
public enum ErrorCode {
    INVALID_PARAMS(-32602),
    INTERNAL_ERROR(-32603),

    /** The path does not exist. */
    INDEX_NOT_FOUND(-32000),
    /** The path is not a directory, or the directory holds no Lucene index. */
    NOT_AN_INDEX(-32001),
    /** The index was written in a format this Lucene version cannot read. */
    UNSUPPORTED_INDEX_FORMAT(-32002),
    /** The index is damaged: a checksum mismatch, a truncated or missing file. */
    CORRUPT_INDEX(-32003),
    /** The method needs an open index, and none is open. */
    NO_INDEX_OPEN(-32004),
    /** The index can't be read: no permission, or another I/O error. */
    INDEX_NOT_READABLE(-32005),
    /** The open index has no field of that name. */
    FIELD_NOT_FOUND(-32006),
    /** No such job: the id is unknown, the job was cancelled, or its outcome was already fetched. */
    JOB_NOT_FOUND(-32007),
    /** The open index has no document with that id: it is negative, or not below maxDoc. */
    DOC_NOT_FOUND(-32008),
    /** The field has no such term, or the document does not contain it. */
    TERM_NOT_FOUND(-32009),
    /** The document has no stored value with that index in the field. */
    VALUE_NOT_FOUND(-32010),
    /** The index directory has no commit of that generation. */
    COMMIT_NOT_FOUND(-32011),
    /** The query can't be run: the expression doesn't parse, or it has too many clauses once rewritten. */
    INVALID_QUERY(-32012),
    /**
     * The analyzer can't be built: an unknown component, an unknown or bad parameter, a config
     * directory that is not a directory, or a resource file that can't be read or parsed.
     */
    INVALID_ANALYZER(-32013),
    /** The index names a codec or format that isn't loaded, and that a jar could add (ADR 0018). */
    MISSING_CODEC(-32014),
    /** The path was opened without a reader (ADR 0021): only its commits can be read. */
    NO_READER(-32015),
    /**
     * A file can't be written (ADR 0022): it lies in an index directory, its directory doesn't exist,
     * it exists, or writing it failed.
     */
    OUTPUT_NOT_WRITABLE(-32016),
    /**
     * Jars can't be added (ADR 0024): a path is neither a jar nor a directory with jars, or their
     * factories, codecs or formats can't be loaded, such as for a missing dependency.
     */
    INVALID_JAR(-32017),
    /**
     * The server doesn't keep that filter (ADR 0031): it is unknown, was released or replaced, or its
     * index was closed or reopened.
     */
    FILTER_NOT_FOUND(-32018),
    /**
     * The open index can't do that: it was written by a Lucene version whose runtime in leia lacks the
     * feature (ADR 0044). {@code openIndex} lists the features that work.
     */
    NOT_SUPPORTED(-32019),
    /**
     * The index was written by a Lucene version that no loaded runtime reads: the runtime's folder in
     * {@code runtimes/} is missing, incomplete or refused (ADR 0044, ADR 0073).
     */
    MISSING_RUNTIME(-32020);

    private final int code;

    ErrorCode(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }
}
