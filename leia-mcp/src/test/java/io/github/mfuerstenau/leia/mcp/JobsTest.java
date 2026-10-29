package io.github.mfuerstenau.leia.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mfuerstenau.leia.protocol.Methods;
import io.github.mfuerstenau.leia.protocol.RpcMethod;
import io.github.mfuerstenau.leia.protocol.document.GetReconstructResult;
import io.github.mfuerstenau.leia.protocol.document.ReconstructResult;
import io.github.mfuerstenau.leia.protocol.document.StartReconstructParams;
import io.github.mfuerstenau.leia.protocol.index.DirectoryImpl;
import io.github.mfuerstenau.leia.protocol.job.CancelJobResult;
import io.github.mfuerstenau.leia.protocol.job.JobParams;
import io.github.mfuerstenau.leia.protocol.job.JobStarted;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/** The polling of a job behind one tool call, against a backend of the test's own. */
class JobsTest {

    private static final StartReconstructParams PARAMS = new StartReconstructParams(1, Optional.empty());

    /** A job 7 that is done at the poll {@code donePoll}, or never; records the calls. */
    private static final class FakeCall implements Call {
        final List<String> calls = new ArrayList<>();
        final int donePoll;
        int polls;

        FakeCall(int donePoll) {
            this.donePoll = donePoll;
        }

        @Override
        public Session session() {
            return new Session(DirectoryImpl.FS, Optional.empty());
        }

        @Override
        @SuppressWarnings("unchecked")
        public <P, R> R call(RpcMethod<P, R> method, P params) {
            calls.add(method.name() + " " + params);
            if (method == Methods.START_RECONSTRUCT) {
                return (R) new JobStarted(7);
            }
            if (method == Methods.CANCEL_JOB) {
                return (R) new CancelJobResult(true);
            }
            polls++;
            // The progress moves on every other poll.
            return (R) new GetReconstructResult(
                    polls / 2,
                    OptionalLong.of(10),
                    polls == donePoll ? Optional.of(new ReconstructResult(List.of(), 3)) : Optional.empty());
        }

        @Override
        public <P, G, T> T job(
                RpcMethod<P, JobStarted> start,
                P params,
                RpcMethod<JobParams, G> get,
                Function<G, Optional<T>> done,
                Function<G, Jobs.Progress> progress) {
            throw new UnsupportedOperationException();
        }
    }

    @Test
    void pollsUntilTheJobIsDoneAndReportsEachChangeOfProgress() throws Exception {
        FakeCall call = new FakeCall(5);
        List<Jobs.Progress> progress = new ArrayList<>();

        ReconstructResult result = Jobs.run(
                call,
                Methods.START_RECONSTRUCT,
                PARAMS,
                Methods.GET_RECONSTRUCT,
                GetReconstructResult::result,
                poll -> new Jobs.Progress(poll.scanned(), poll.total()),
                progress::add,
                () -> false);

        assertThat(result.tookMillis()).isEqualTo(3);
        assertThat(call.polls).isEqualTo(5);
        assertThat(progress)
                .containsExactly(
                        new Jobs.Progress(0, OptionalLong.of(10)),
                        new Jobs.Progress(1, OptionalLong.of(10)),
                        new Jobs.Progress(2, OptionalLong.of(10)));
        assertThat(call.calls).noneMatch(line -> line.startsWith("cancelJob"));
    }

    @Test
    void cancelsTheJobWhenTheRequestIsCancelled() {
        FakeCall call = new FakeCall(-1);

        assertThatThrownBy(() -> Jobs.run(
                        call,
                        Methods.START_RECONSTRUCT,
                        PARAMS,
                        Methods.GET_RECONSTRUCT,
                        GetReconstructResult::result,
                        poll -> new Jobs.Progress(poll.scanned(), poll.total()),
                        _ -> {},
                        () -> call.polls >= 3))
                .isInstanceOf(Jobs.Cancelled.class);

        assertThat(call.polls).isEqualTo(3);
        assertThat(call.calls).last().isEqualTo("cancelJob " + new JobParams(7));
    }
}
