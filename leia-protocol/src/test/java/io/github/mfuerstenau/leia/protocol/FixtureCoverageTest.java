package io.github.mfuerstenau.leia.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;

/** The fixtures and the Java DTOs describe the same protocol, with nothing missing on either side. */
class FixtureCoverageTest {

    private final List<Fixture> fixtures = Fixture.all();

    @Test
    void everyMethodHasRequestAndResponseFixtures() {
        for (RpcMethod<?, ?> method : Methods.ALL) {
            assertThat(fixtures)
                    .as("fixtures of %s", method.name())
                    .anySatisfy(fixture -> assertThat(fixture)
                            .extracting(Fixture::method, Fixture::kind)
                            .containsExactly(method.name(), Fixture.Kind.REQUEST))
                    .anySatisfy(fixture -> assertThat(fixture)
                            .extracting(Fixture::method, Fixture::kind)
                            .containsExactly(method.name(), Fixture.Kind.RESPONSE));
        }
    }

    @Test
    void everyFixtureBelongsToAMethod() {
        for (Fixture fixture : fixtures) {
            if (fixture.method().equals(Fixture.RPC)) {
                assertThat(fixture.kind()).as("kind of %s", fixture).isEqualTo(Fixture.Kind.ERROR);
            } else {
                assertThat(Methods.byName(fixture.method()))
                        .as("method of %s", fixture)
                        .isPresent();
            }
        }
    }

    @Test
    void methodNamesAreUnique() {
        assertThat(Methods.ALL).extracting(RpcMethod::name).doesNotHaveDuplicates();
    }

    @Test
    void errorCodesMatchTheCatalogue() {
        Map<String, Integer> catalogue = ProtocolJson.mapper()
                .readValue(
                        Fixture.protocolDir().resolve("error-codes.json").toFile(),
                        new TypeReference<LinkedHashMap<String, Integer>>() {});
        Map<String, Integer> enumCodes = Arrays.stream(ErrorCode.values())
                .collect(Collectors.toMap(Enum::name, ErrorCode::code, (a, b) -> a, LinkedHashMap::new));

        assertThat(enumCodes).containsExactlyEntriesOf(catalogue);
    }
}
