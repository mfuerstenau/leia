package io.github.mfuerstenau.leia.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mfuerstenau.leia.mcp.Call;
import io.github.mfuerstenau.leia.mcp.Jobs;
import io.github.mfuerstenau.leia.mcp.Session;
import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.RpcMethod;
import io.github.mfuerstenau.leia.protocol.document.DocumentField;
import io.github.mfuerstenau.leia.protocol.document.GetDocumentResult;
import io.github.mfuerstenau.leia.protocol.document.GetStoredValueResult;
import io.github.mfuerstenau.leia.protocol.document.StoredType;
import io.github.mfuerstenau.leia.protocol.document.StoredValue;
import io.github.mfuerstenau.leia.protocol.job.JobStarted;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Function;
import java.util.function.LongFunction;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

class GetDocumentToolTest {

    /** Answers {@code getDocument} with one stored value, {@code getStoredValue} with {@code whole}. */
    private static final class FakeCall implements Call {
        final List<String> methods = new ArrayList<>();
        private final StoredValue cut;
        private final String whole;

        FakeCall(StoredValue cut, String whole) {
            this.cut = cut;
            this.whole = whole;
        }

        @Override
        public Session session() {
            throw new UnsupportedOperationException();
        }

        @Override
        @SuppressWarnings("unchecked")
        public <P, R> R call(RpcMethod<P, R> method, P params) {
            methods.add(method.name());
            if (method == Methods.GET_DOCUMENT) {
                DocumentField field = new DocumentField(
                        "body", OptionalLong.empty(), List.of(cut), Optional.empty(), false, Optional.empty());
                return (R) new GetDocumentResult(3, false, List.of(field));
            }
            if (method == Methods.GET_STORED_VALUE) {
                StoredValue head = new StoredValue(StoredType.STRING, whole.substring(0, 5), false);
                return (R) new GetStoredValueResult(head, 0, whole.length());
            }
            throw new UnsupportedOperationException(method.name());
        }

        @Override
        public <P, Q, G, T> T job(
                RpcMethod<P, JobStarted> start,
                P params,
                RpcMethod<Q, G> get,
                LongFunction<Q> poll,
                Function<G, Optional<T>> done,
                Predicate<G> finished,
                Function<G, Jobs.Progress> progress) {
            throw new UnsupportedOperationException();
        }
    }

    private static GetDocumentTool.Arguments arguments(int maxValueLength) {
        return new GetDocumentTool.Arguments(3, null, maxValueLength, null);
    }

    @Test
    void takesTheLengthInCharactersFromTheBackend() throws Exception {
        String head = "x".repeat(100);
        FakeCall call = new FakeCall(
                new StoredValue(StoredType.STRING, head, false, OptionalLong.of(70_000), OptionalLong.of(69_000)), "");

        GetDocumentTool.Result result = new GetDocumentTool().call(arguments(10), call);

        GetDocumentTool.Value value = result.fields().get(0).stored().get(0);
        assertThat(value.value()).isEqualTo("x".repeat(10));
        assertThat(value.fullLength()).isEqualTo(69_000L);
        assertThat(call.methods).containsExactly(Methods.GET_DOCUMENT.name());
    }

    @Test
    void readsTheValueAgainWhenTheHeadIsShorterThanAsked() throws Exception {
        // A head of 4 characters holds 64 KiB of UTF-8 only if they are 3 bytes each, say.
        FakeCall call = new FakeCall(
                new StoredValue(StoredType.STRING, "abcd", false, OptionalLong.of(70_000), OptionalLong.of(40_000)),
                "abcdefgh");

        GetDocumentTool.Result result = new GetDocumentTool().call(arguments(6), call);

        GetDocumentTool.Value value = result.fields().get(0).stored().get(0);
        assertThat(value.value()).isEqualTo("abcde");
        assertThat(value.fullLength()).isEqualTo(8L);
        assertThat(call.methods).containsExactly(Methods.GET_DOCUMENT.name(), Methods.GET_STORED_VALUE.name());
    }
}
