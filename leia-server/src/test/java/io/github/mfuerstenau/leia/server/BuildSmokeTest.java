package io.github.mfuerstenau.leia.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mfuerstenau.leia.server.index.Routing;
import org.apache.lucene.codecs.Codec;
import org.junit.jupiter.api.Test;

class BuildSmokeTest {

    @Test
    void daggerGeneratesComponent() {
        assertThat(DaggerServerComponent.factory().create(Routing.DEFAULT)).isNotNull();
    }

    @Test
    void buildInfoIsGenerated() {
        BuildInfo info = BuildInfo.load();
        assertThat(info.version()).isNotBlank().isNotEqualTo("unknown");
        assertThat(info.commit()).isNotBlank();
    }

    @Test
    void backwardCodecsAreOnTheRuntimeClasspath() {
        assertThat(Codec.availableCodecs())
                .contains(Codec.getDefault().getName())
                .contains("Lucene99", "Lucene90");
    }
}
