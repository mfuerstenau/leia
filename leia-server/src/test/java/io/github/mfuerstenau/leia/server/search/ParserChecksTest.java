package io.github.mfuerstenau.leia.server.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.search.DateResolution;
import io.github.mfuerstenau.leia.protocol.search.FieldDateResolution;
import io.github.mfuerstenau.leia.server.RpcException;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ParserChecksTest {

    @Test
    void takesTheClassicOptionsLuceneTakes() throws RpcException {
        ParserChecks.classic(0, false, false);
        ParserChecks.classic(2, true, true);

        assertThatThrownBy(() -> ParserChecks.classic(-1, false, false))
                .isInstanceOfSatisfying(
                        RpcException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_PARAMS))
                .hasMessage("Invalid params: fuzzyMaxEdits must be 0 to 2, not -1");
        assertThatThrownBy(() -> ParserChecks.classic(2, false, true))
                .isInstanceOf(RpcException.class)
                .hasMessage("Invalid params: autoGeneratePhraseQueries needs splitOnWhitespace");
    }

    @Test
    void readsTheFieldDateResolutionsInTheirOrder() throws RpcException {
        FieldDateResolution b = new FieldDateResolution("b", DateResolution.DAY);
        FieldDateResolution a = new FieldDateResolution("a", DateResolution.HOUR);

        assertThat(ParserChecks.fieldDateResolutions(List.of(b, a), _ -> {}))
                .containsExactly(Map.entry("b", DateResolution.DAY), Map.entry("a", DateResolution.HOUR));
        assertThatThrownBy(() -> ParserChecks.fieldDateResolutions(List.of(b, b), _ -> {}))
                .isInstanceOf(RpcException.class)
                .hasMessage("Invalid params: the field 'b' has its date resolution listed twice");
        RpcException missing = new RpcException(ErrorCode.FIELD_NOT_FOUND, "No field 'a' in the index");
        assertThatThrownBy(() -> ParserChecks.fieldDateResolutions(List.of(a), _ -> {
                    throw missing;
                }))
                .isSameAs(missing);
    }

    @Test
    void readsLocalesAndTimeZones() throws RpcException {
        assertThat(ParserChecks.locale("de-DE")).isEqualTo(Locale.GERMANY);
        assertThat(ParserChecks.timeZone("+02:00")).isEqualTo(ZoneId.of("+02:00"));

        assertThatThrownBy(() -> ParserChecks.locale(" "))
                .isInstanceOf(RpcException.class)
                .hasMessage("Invalid params: ' ' is not a BCP 47 language tag, such as en-US");
        assertThatThrownBy(() -> ParserChecks.timeZone("Mars"))
                .isInstanceOf(RpcException.class)
                .hasMessage("Invalid params: 'Mars' is not a time zone, such as UTC, Europe/Berlin or +02:00");
    }
}
