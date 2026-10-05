package io.github.mfuerstenau.leia.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Every fixture survives deserialize → serialize unchanged (ADR 0001). */
class FixtureRoundTripTest {

    private static final JsonMapper MAPPER = ProtocolJson.mapper();

    static List<Fixture> fixtures() {
        return Fixture.all();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void roundTrips(Fixture fixture) {
        String json = fixture.read();

        Object message = MAPPER.readValue(json, typeOf(fixture));

        switch (fixture.kind()) {
            case REQUEST -> assertThat(((Request<?>) message).method()).isEqualTo(fixture.method());
            case RESPONSE -> assertThat(((Response<?>) message).result()).isPresent();
            case ERROR -> assertThat(((Response<?>) message).error()).isPresent();
        }
        JsonNode expected = MAPPER.readTree(json);
        JsonNode actual = MAPPER.readTree(MAPPER.writeValueAsString(message));
        assertThat(actual).isEqualTo(expected);
    }

    private static JavaType typeOf(Fixture fixture) {
        if (fixture.method().equals(Fixture.RPC)) {
            return ProtocolJson.responseType(Void.class);
        }
        RpcMethod<?, ?> method = Methods.byName(fixture.method())
                .orElseThrow(() -> new AssertionError("No method " + fixture.method() + " in Methods"));
        return fixture.kind() == Fixture.Kind.REQUEST
                ? ProtocolJson.requestType(method)
                : ProtocolJson.responseType(method);
    }
}
