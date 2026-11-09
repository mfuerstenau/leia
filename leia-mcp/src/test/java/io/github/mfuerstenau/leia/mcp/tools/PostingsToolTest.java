package io.github.mfuerstenau.leia.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mfuerstenau.leia.mcp.Call;
import io.github.mfuerstenau.leia.mcp.Jobs;
import io.github.mfuerstenau.leia.mcp.Session;
import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.RpcMethod;
import io.github.mfuerstenau.leia.protocol.document.TermPosition;
import io.github.mfuerstenau.leia.protocol.index.DirectoryImpl;
import io.github.mfuerstenau.leia.protocol.index.GetIndexStatsResult;
import io.github.mfuerstenau.leia.protocol.job.JobStarted;
import io.github.mfuerstenau.leia.protocol.terms.GetPostingsParams;
import io.github.mfuerstenau.leia.protocol.terms.GetPostingsResult;
import io.github.mfuerstenau.leia.protocol.terms.Posting;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Function;
import java.util.function.LongFunction;
import java.util.function.Predicate;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class PostingsToolTest {

    /** Answers {@code getPostings} with the positions it is asked for, of a term that occurs {@code freq} times. */
    private static final class FakeCall implements Call {
        private final int freq;
        final List<String> methods = new ArrayList<>();
        final List<GetPostingsParams> postings = new ArrayList<>();

        FakeCall(int freq) {
            this.freq = freq;
        }

        @Override
        public Session session() {
            return new Session(DirectoryImpl.FS, null, List.of());
        }

        @Override
        @SuppressWarnings("unchecked")
        public <P, R> R call(RpcMethod<P, R> method, P params) {
            methods.add(method.name());
            if (method == Methods.GET_INDEX_STATS) {
                return (R) new GetIndexStatsResult("index", "FS", 1, 2, 2, List.of(), List.of());
            }
            if (method == Methods.GET_POSTINGS) {
                GetPostingsParams asked = (GetPostingsParams) params;
                postings.add(asked);
                List<Posting> page = new ArrayList<>();
                for (int doc = 0; doc < 2; doc++) {
                    page.add(new Posting(
                            doc,
                            false,
                            OptionalInt.of(freq),
                            Optional.empty(),
                            asked.maxPositions().isPresent()
                                    ? Optional.of(positions(
                                            Math.min(freq, asked.maxPositions().getAsInt())))
                                    : Optional.empty()));
                }
                return (R) new GetPostingsResult(2, 2L * freq, page, OptionalInt.empty());
            }
            throw new UnsupportedOperationException(method.name());
        }

        private static List<TermPosition> positions(int count) {
            return IntStream.range(0, count)
                    .mapToObj(i -> new TermPosition(
                            OptionalInt.of(i), OptionalInt.empty(), OptionalInt.empty(), Optional.empty()))
                    .toList();
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

    private static PostingsTool.Arguments arguments(boolean positions) {
        return new PostingsTool.Arguments("body", "quick", null, null, null, positions);
    }

    @Test
    void readsThePositionsWithOneCallAndCutsThem() throws Exception {
        FakeCall call = new FakeCall(150);

        PostingsTool.Result result = new PostingsTool().call(arguments(true), call);

        assertThat(call.methods).containsExactly(Methods.GET_INDEX_STATS.name(), Methods.GET_POSTINGS.name());
        assertThat(call.postings.getFirst().maxPositions()).hasValue(PostingsTool.MAX_POSITIONS + 1);
        assertThat(result.postings()).hasSize(2).allSatisfy(entry -> {
            assertThat(entry.positions()).hasSize(PostingsTool.MAX_POSITIONS);
            assertThat(entry.positionsCut()).isTrue();
        });
    }

    @Test
    void doesNotCutADocumentWithExactlyTheMostPositions() throws Exception {
        FakeCall call = new FakeCall(PostingsTool.MAX_POSITIONS);

        PostingsTool.Result result = new PostingsTool().call(arguments(true), call);

        assertThat(result.postings()).allSatisfy(entry -> {
            assertThat(entry.positions()).hasSize(PostingsTool.MAX_POSITIONS);
            assertThat(entry.positionsCut()).isNull();
        });
    }

    @Test
    void asksForNoPositionsWithoutThem() throws Exception {
        FakeCall call = new FakeCall(150);

        PostingsTool.Result result = new PostingsTool().call(arguments(false), call);

        assertThat(call.methods).containsExactly(Methods.GET_POSTINGS.name());
        assertThat(call.postings.getFirst().maxPositions()).isEmpty();
        assertThat(result.postings())
                .allSatisfy(entry -> assertThat(entry.positions()).isNull());
    }
}
