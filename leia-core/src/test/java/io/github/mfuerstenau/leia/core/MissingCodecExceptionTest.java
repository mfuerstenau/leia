package io.github.mfuerstenau.leia.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mfuerstenau.leia.core.MissingCodecException.Kind;
import org.apache.lucene.codecs.Codec;
import org.apache.lucene.codecs.DocValuesFormat;
import org.apache.lucene.codecs.KnnVectorsFormat;
import org.apache.lucene.codecs.PostingsFormat;
import org.apache.lucene.util.Version;
import org.junit.jupiter.api.Test;

class MissingCodecExceptionTest {

    @Test
    void findsTheKindAndNameOfEveryLookup() {
        assertMissing(lookup(() -> Codec.forName("Custom")), Kind.CODEC, "Custom");
        assertMissing(lookup(() -> PostingsFormat.forName("Custom")), Kind.POSTINGS_FORMAT, "Custom");
        assertMissing(lookup(() -> DocValuesFormat.forName("Custom")), Kind.DOC_VALUES_FORMAT, "Custom");
        assertMissing(lookup(() -> KnnVectorsFormat.forName("Custom")), Kind.KNN_VECTORS_FORMAT, "Custom");
    }

    @Test
    void saysThatTheNameIsNotLoaded() {
        MissingCodecException e = MissingCodecException.of(lookup(() -> PostingsFormat.forName("Custom")))
                .orElseThrow();

        assertThat(e.isLucenes()).isFalse();
        assertThat(e).hasMessage("the index needs the postings format 'Custom', which isn't loaded");
    }

    @Test
    void lucenesOwnNamesNeedAnotherLuceneVersion() {
        MissingCodecException e = MissingCodecException.of(lookup(() -> Codec.forName("Lucene999")))
                .orElseThrow();

        assertThat(e.isLucenes()).isTrue();
        assertThat(e)
                .hasMessage("Lucene " + Version.LATEST
                        + " has no codec 'Lucene999'; the index needs another Lucene version");
    }

    @Test
    void findsTheLookupAmongTheCauses() {
        // As SegmentInfos wraps a codec lookup that fails.
        RuntimeException wrapped = new IllegalArgumentException(
                "Could not load codec 'Lucene999'. Did you forget to add lucene-backward-codecs.jar?",
                lookup(() -> Codec.forName("Lucene999")));

        assertMissing(wrapped, Kind.CODEC, "Lucene999");
    }

    @Test
    void otherExceptionsAreNoMissingCodec() {
        assertThat(MissingCodecException.of(new IllegalArgumentException("No field title")))
                .isEmpty();
        assertThat(MissingCodecException.of(new IllegalStateException())).isEmpty();
    }

    @Test
    void lucenesCodecsAndSuggestersAreBundled() {
        assertThat(Codec.availableCodecs()).contains("SimpleText");
        assertThat(PostingsFormat.availablePostingsFormats())
                .contains("BlockTreeOrds", "FST50")
                .anyMatch(name -> name.startsWith("Completion"));
    }

    private static void assertMissing(RuntimeException e, Kind kind, String name) {
        assertThat(MissingCodecException.of(e)).hasValueSatisfying(missing -> {
            assertThat(missing.kind()).isEqualTo(kind);
            assertThat(missing.name()).isEqualTo(name);
            assertThat(missing).cause().isInstanceOf(IllegalArgumentException.class);
        });
    }

    /** The exception that {@code lookup} throws. */
    private static RuntimeException lookup(Runnable lookup) {
        try {
            lookup.run();
        } catch (RuntimeException e) {
            return e;
        }
        throw new AssertionError("the lookup didn't fail");
    }
}
