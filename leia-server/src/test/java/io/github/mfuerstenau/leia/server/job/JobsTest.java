package io.github.mfuerstenau.leia.server.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.server.RpcException;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

class JobsTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final Jobs jobs = new Jobs();

    @Test
    void handsOutTheResultOnce() throws Exception {
        long id = jobs.start("test", String.class, progress -> {
            progress.scanned(7);
            return "done";
        });

        assertThat(awaitDone(id)).isEqualTo(new Jobs.Poll<>(7, Optional.of("done")));
        assertFails(() -> jobs.poll(id, String.class), ErrorCode.JOB_NOT_FOUND);
    }

    @Test
    void reportsProgressWhileRunning() throws Exception {
        CountDownLatch reported = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        long id = jobs.start("test", String.class, progress -> {
            progress.scanned(42);
            reported.countDown();
            await(release);
            return "done";
        });

        assertThat(reported.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
        assertThat(jobs.poll(id, String.class)).isEqualTo(new Jobs.Poll<String>(42, Optional.empty()));
        release.countDown();
        assertThat(awaitDone(id).result()).contains("done");
    }

    @Test
    void handsOutTheFailureOnce() throws Exception {
        long id = jobs.start("test", String.class, progress -> {
            throw new RpcException(ErrorCode.INDEX_NOT_READABLE, "Cannot read");
        });

        awaitFinished(id);
        assertThatThrownBy(() -> jobs.poll(id, String.class))
                .isInstanceOfSatisfying(
                        RpcException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.INDEX_NOT_READABLE))
                .hasMessage("Cannot read");
        assertFails(() -> jobs.poll(id, String.class), ErrorCode.JOB_NOT_FOUND);
    }

    @Test
    void reportsAnUnexpectedExceptionAsInternalError() throws Exception {
        long id = jobs.start("test", String.class, progress -> {
            throw new IllegalStateException("bug");
        });

        awaitFinished(id);
        assertThatThrownBy(() -> jobs.poll(id, String.class))
                .isInstanceOfSatisfying(
                        RpcException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.INTERNAL_ERROR))
                .hasMessageContaining("bug");
    }

    @Test
    void rejectsUnknownIdAndOtherResultType() throws Exception {
        long id = jobs.start("test", String.class, progress -> "done");

        assertFails(() -> jobs.poll(id + 1, String.class), ErrorCode.JOB_NOT_FOUND);
        assertFails(() -> jobs.poll(id, Integer.class), ErrorCode.JOB_NOT_FOUND);
        assertThat(awaitDone(id).result()).contains("done");
    }

    @Test
    void cancelStopsTheJobAndForgetsIt() throws Exception {
        AtomicBoolean stopped = new AtomicBoolean();
        long id = startEndlessJob(stopped);

        assertThat(jobs.cancel(id)).isTrue();

        assertThat(stopped).as("cancel waits until the job has stopped").isTrue();
        assertFails(() -> jobs.poll(id, String.class), ErrorCode.JOB_NOT_FOUND);
        assertThat(jobs.cancel(id)).isFalse();
    }

    @Test
    void cancellingAFinishedOrUnknownJobIsNoError() throws Exception {
        long id = jobs.start("test", String.class, progress -> "done");
        awaitFinished(id);

        assertThat(jobs.cancel(id)).isFalse();
        assertThat(jobs.cancel(1234)).isFalse();
        assertFails(() -> jobs.poll(id, String.class), ErrorCode.JOB_NOT_FOUND);
    }

    @Test
    void cancelAllStopsEveryJob() throws Exception {
        AtomicBoolean firstStopped = new AtomicBoolean();
        AtomicBoolean secondStopped = new AtomicBoolean();
        long first = startEndlessJob(firstStopped);
        long second = startEndlessJob(secondStopped);

        jobs.cancelAll();

        assertThat(firstStopped).isTrue();
        assertThat(secondStopped).isTrue();
        assertFails(() -> jobs.poll(first, String.class), ErrorCode.JOB_NOT_FOUND);
        assertFails(() -> jobs.poll(second, String.class), ErrorCode.JOB_NOT_FOUND);
    }

    /** Starts a job that reports progress until it is cancelled, and waits until it runs. */
    private long startEndlessJob(AtomicBoolean stopped) throws InterruptedException {
        CountDownLatch started = new CountDownLatch(1);
        long id = jobs.start("endless", String.class, progress -> {
            started.countDown();
            try {
                for (long scanned = 0; ; scanned++) {
                    progress.scanned(scanned);
                }
            } finally {
                stopped.set(true);
            }
        });
        assertThat(started.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
        return id;
    }

    private Jobs.Poll<String> awaitDone(long id) throws Exception {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            Jobs.Poll<String> poll = jobs.poll(id, String.class);
            if (poll.result().isPresent()) {
                return poll;
            }
            Thread.sleep(5);
        }
        throw new AssertionError("job " + id + " did not finish");
    }

    /** Waits until the job has an outcome, without fetching it. */
    private void awaitFinished(long id) throws InterruptedException {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (!jobs.isDone(id)) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("job " + id + " did not finish");
            }
            Thread.sleep(5);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void assertFails(ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        RpcException.class, e -> assertThat(e.code()).isEqualTo(code));
    }
}
