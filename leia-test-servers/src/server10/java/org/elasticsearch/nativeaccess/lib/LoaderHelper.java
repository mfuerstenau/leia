package org.elasticsearch.nativeaccess.lib;

import java.nio.file.Path;

/**
 * A stand-in for Elasticsearch's, which takes the folder of its native libraries from {@code
 * es.nativelibs.path} when it is initialized, and fails without it (ADR 0090).
 */
public final class LoaderHelper {

    public static final Path platformLibDir = Path.of(System.getProperty("es.nativelibs.path"));

    private LoaderHelper() {}
}
