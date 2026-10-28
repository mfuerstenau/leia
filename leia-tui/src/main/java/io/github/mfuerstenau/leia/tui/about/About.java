package io.github.mfuerstenau.leia.tui.about;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** What the about window says that leia's version doesn't: where the project is, who wrote it, its licence. */
public final class About {

    /** Where leia's source is. */
    public static final String URL = "https://github.com/mfuerstenau/leia";

    /** Where the licence of leia and of Lucene is. */
    public static final String LICENSE_URL = "https://www.apache.org/licenses/LICENSE-2.0";

    /** The NOTICE file, which {@code processResources} puts at a path of leia's own, not {@code META-INF/NOTICE}. */
    static final String NOTICE_RESOURCE = "NOTICE";

    static final String NOTICE_MISSING = "NOTICE not found";

    private static final Pattern COPYRIGHT = Pattern.compile("(?m)^Copyright \\d{4}(?:-\\d{4})? (.+?)\\s*$");

    private About() {}

    /** The text of the NOTICE file of leia.jar, or {@link #NOTICE_MISSING} where there is none, as in a test. */
    public static String notice() {
        try (InputStream stream = About.class.getResourceAsStream(NOTICE_RESOURCE)) {
            return stream == null ? NOTICE_MISSING : new String(stream.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            return NOTICE_MISSING;
        }
    }

    /** The rest of the first line of {@code notice} that starts with {@code Copyright <year> }. */
    static Optional<String> author(String notice) {
        Matcher matcher = COPYRIGHT.matcher(notice);
        return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
    }
}
