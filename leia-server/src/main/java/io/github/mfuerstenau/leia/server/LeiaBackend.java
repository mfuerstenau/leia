package io.github.mfuerstenau.leia.server;

import io.github.mfuerstenau.leia.protocol.ErrorCode;
import io.github.mfuerstenau.leia.protocol.RpcMethod;
import io.github.mfuerstenau.leia.server.jars.JarChain;
import io.github.mfuerstenau.leia.server.jars.JarException;
import io.github.mfuerstenau.leia.server.runtime.RuntimeFolders;
import io.github.mfuerstenau.leia.server.runtime.Runtimes;
import io.github.mfuerstenau.leia.server.runtime.RuntimesDir;
import io.github.mfuerstenau.leia.server.runtime.ServerException;
import io.github.mfuerstenau.leia.server.runtime.ServerRuntimes;
import io.github.mfuerstenau.leia.server.stored.StoredFieldsCache;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The server's methods, called in-process by the UI (ADR 0034): Every {@link #call} runs on one
 * backend thread, in the order of the calls, so the UI thread never waits for Lucene.
 */
public final class LeiaBackend implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(LeiaBackend.class.getName());

    /** How long {@link #close} waits for the backend thread, which stops the jobs first. */
    private static final Duration CLOSE_TIMEOUT = Duration.ofMinutes(1);

    private final ExecutorService executor;
    private final Handlers handlers;
    private final Runnable onClose;
    private final AtomicBoolean closed = new AtomicBoolean();

    private LeiaBackend(ExecutorService executor, Handlers handlers, Runnable onClose) {
        this.executor = executor;
        this.handlers = handlers;
        this.onClose = onClose;
    }

    /** A backend with {@code handlers}, which runs {@code onClose} on its thread when it is closed. */
    LeiaBackend(Handlers handlers, Runnable onClose) {
        this(newExecutor(), handlers, onClose);
        // As start() does, so that tests see the same thread.
        await(CompletableFuture.runAsync(
                () -> Thread.currentThread().setContextClassLoader(JarChain.classLoader()), executor));
    }

    /**
     * Loads the runtimes of {@code runtimesDir} (ADR 0073) and the jars with analysis components,
     * codecs or formats of their own (ADR 0016, ADR 0018), and starts the backend thread.
     *
     * @param runtimesDir the runtimes folder (ADR 0098), if there is one; a
     *     runtime in it that is missing, incomplete or refused is not fatal, but {@code getRuntimes}
     *     reports it
     * @param jars jars, or directories of jars
     * @throws RpcException {@code INVALID_JAR} if the jars can't be loaded, or bring a runtime that
     *     leia refuses (ADR 0044); nothing is started then
     */
    public static LeiaBackend start(Optional<Path> runtimesDir, List<Path> jars) throws RpcException {
        return start(runtimesDir, jars, List.of());
    }

    /**
     * As {@link #start(Optional, List)}, and loads the runtimes of the Elasticsearch and OpenSearch
     * installations at {@code servers} after those of {@code runtimesDir}, whose adapters they use
     * (ADR 0090).
     *
     * @param servers each an installation's home or its {@code lib/}
     * @throws RpcException {@code INVALID_JAR} also if a server can't be loaded
     */
    public static LeiaBackend start(Optional<Path> runtimesDir, List<Path> jars, List<Path> servers)
            throws RpcException {
        return start(runtimesDir, jars, servers, Optional.empty());
    }

    /**
     * As {@link #start(Optional, List, List)}, and keeps the cache of the stored fields on disk below
     * {@code cacheDir} too (ADR 0113).
     *
     * @param cacheDir leia's cache folder, such as {@code ~/.cache/leia}; empty to cache in memory only
     */
    public static LeiaBackend start(
            Optional<Path> runtimesDir, List<Path> jars, List<Path> servers, Optional<Path> cacheDir)
            throws RpcException {
        return startWith(runtimesDir.map(RuntimesDir::user).stream().toList(), jars, servers, cacheDir);
    }

    /**
     * As {@link #start(Optional, List, List, Optional)}, with the runtimes looked up one at a time in
     * {@code runtimesDirs}, in order (ADR 0122).
     *
     * @param runtimesDirs the runtimes folders, user's first; empty if leia has none
     */
    public static LeiaBackend startWith(
            List<RuntimesDir> runtimesDirs, List<Path> jars, List<Path> servers, Optional<Path> cacheDir)
            throws RpcException {
        // Lucene 10's folder first: the jars link against its Lucene (ADR 0065).
        if (!runtimesDirs.isEmpty()) {
            RuntimeFolders.load(runtimesDirs);
        }
        try {
            ServerRuntimes.load(servers, runtimesDirs);
        } catch (ServerException e) {
            throw new RpcException(ErrorCode.INVALID_JAR, e.getMessage(), e);
        }
        try {
            List<Path> loaded = JarChain.load(jars);
            if (!loaded.isEmpty()) {
                LOG.info(() -> "Loaded the jars " + loaded);
            }
        } catch (JarException e) {
            throw new RpcException(ErrorCode.INVALID_JAR, "Cannot load the jars: " + e.getMessage(), e);
        }
        List<String> refused = Runtimes.load(JarChain.classLoader()).refused().stream()
                .filter(refusal -> !RuntimeFolders.chainRefusals().contains(refusal))
                .toList();
        if (!refused.isEmpty()) {
            throw new RpcException(ErrorCode.INVALID_JAR, "Cannot load the jars: " + String.join("; ", refused));
        }
        ExecutorService executor = newExecutor();
        ServerComponent component = await(CompletableFuture.supplyAsync(
                () -> {
                    // For libraries in the jars that load through the context class loader; the job
                    // threads, started from this thread, inherit it. addJars sets it again (ADR 0024).
                    Thread.currentThread().setContextClassLoader(JarChain.classLoader());
                    return DaggerServerComponent.factory().create(new StoredFieldsCache(cacheDir));
                },
                executor));
        BuildInfo buildInfo = BuildInfo.load();
        LOG.info(() -> "leia backend " + buildInfo.version() + " (" + buildInfo.commit() + ") ready on Java "
                + Runtime.version());
        // A UI that quits without closeIndex leaves the index open.
        return new LeiaBackend(
                executor, component.handlers(), () -> component.currentIndex().close());
    }

    /**
     * Runs {@code method} on the backend thread, after the calls before it.
     *
     * <p>The future completes on the backend thread, so the UI has to hand its outcome to its own
     * thread. It fails with an {@link RpcException} for an expected failure, with its {@link
     * ErrorCode}, and with one with {@code INTERNAL_ERROR} for any other {@link RuntimeException}. An
     * {@link Error} fails it as it is. After {@link #close}, it fails with an {@link
     * IllegalStateException}.
     */
    public <P, R> CompletableFuture<R> call(RpcMethod<P, R> method, P params) {
        Objects.requireNonNull(params, "params");
        MethodHandler<P, R> handler = handlers.get(method);
        CompletableFuture<R> result = new CompletableFuture<>();
        try {
            executor.execute(() -> run(handler, params, result));
        } catch (RejectedExecutionException e) {
            result.completeExceptionally(new IllegalStateException("The backend is closed", e));
        }
        return result;
    }

    // A LinkageError must still complete the future (ADR 0088)
    @SuppressWarnings("java:S1181")
    private static <P, R> void run(MethodHandler<P, R> handler, P params, CompletableFuture<R> result) {
        String name = handler.method().name();
        try {
            R value = Handlers.run(handler, params);
            LOG.fine(() -> name + " " + params + System.lineSeparator() + "-> " + value);
            result.complete(value);
        } catch (RpcException e) {
            LOG.fine(() -> name + " " + params + System.lineSeparator() + "-> " + e.code() + ": " + e.getMessage());
            result.completeExceptionally(e);
        } catch (Error e) {
            // A server's Lucene that lacks what the adapter calls, or a class of the server that fails
            // (ADR 0090); else such as a LinkageError from a jar. The future must not wait forever.
            Optional<RpcException> notSupported = ServerRuntimes.notSupported(e);
            if (notSupported.isPresent()) {
                LOG.log(Level.WARNING, e, () -> name + " " + params + " fails in the server's classes");
                result.completeExceptionally(notSupported.get());
            } else {
                LOG.log(Level.SEVERE, e, () -> "Method " + name + " failed");
                result.completeExceptionally(e);
            }
        }
    }

    /**
     * Closes the open index, if any, after the calls before, and stops the backend thread. Calls
     * after this fail.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        executor.execute(onClose);
        // shutdown, not shutdownNow: never interrupt a thread that reads an index (AGENTS.md, rule 7).
        executor.shutdown();
        try {
            if (!executor.awaitTermination(CLOSE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                LOG.warning(() -> "The backend did not stop within " + CLOSE_TIMEOUT.toSeconds() + " s");
            }
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }

    private static ExecutorService newExecutor() {
        return Executors.newSingleThreadExecutor(
                Thread.ofPlatform().daemon().name("leia-backend").factory());
    }

    private static <T> T await(CompletableFuture<T> future) {
        try {
            return future.get();
        } catch (ExecutionException e) {
            throw e.getCause() instanceof RuntimeException runtime
                    ? runtime
                    : new IllegalStateException("Cannot start the backend", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while starting the backend", e);
        }
    }
}
