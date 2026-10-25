package io.github.mfuerstenau.leia.server.runtime;

import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * What OpenSearch's node sets up before the codecs of its k-NN plugin work, done for a server's class
 * loader (ADR 0092): the plugin's {@code KNNSettings} read their values from the node's {@code
 * ClusterService}, which the plugin is given when it starts. Its codecs ask for it as an index is
 * closed (the native memory cache of {@code KNN80DocValuesProducer}) and checked, and a native
 * engine's reader as it is searched, so without one every close and every check of a k-NN index
 * fails with a {@code NullPointerException}.
 *
 * <p>leia gives it a {@code ClusterService} of its own, with the default value of every setting: the
 * cluster settings that OpenSearch has built in, the plugin's, and a thread pool without executors,
 * which the {@code ClusterService} needs to be made. No node starts. Done reflectively, since leia
 * has no OpenSearch to link against.
 *
 * <p>Best effort: what fails is logged, and the calls that need it fail as they would without it.
 */
final class OpenSearchKnnSetup {

    private static final Logger LOG = Logger.getLogger(OpenSearchKnnSetup.class.getName());

    private static final String KNN_SETTINGS = "org.opensearch.knn.index.KNNSettings";

    private OpenSearchKnnSetup() {}

    /** Gives the k-NN plugin that {@code loader} sees its cluster service; does nothing if it has none. */
    static void setUp(ClassLoader loader, Path home) {
        Class<?> knnSettings;
        try {
            knnSettings = Class.forName(KNN_SETTINGS, true, loader);
        } catch (ClassNotFoundException _) {
            return;
        } catch (LinkageError e) {
            LOG.log(Level.WARNING, e, () -> "Cannot load the k-NN plugin of OpenSearch in " + home);
            return;
        }
        try {
            Object state = knnSettings.getMethod("state").invoke(null);
            Class<?> clusterService = Class.forName("org.opensearch.cluster.service.ClusterService", false, loader);
            knnSettings
                    .getMethod("setClusterService", clusterService)
                    .invoke(state, clusterService(loader, state, clusterService));
            LOG.fine(() -> "Gave the k-NN plugin of OpenSearch in " + home + " a cluster service");
        } catch (InvocationTargetException e) {
            LOG.log(Level.WARNING, e.getCause(), () -> "Cannot set up the k-NN plugin of OpenSearch in " + home);
        } catch (ReflectiveOperationException | LinkageError e) {
            LOG.log(Level.WARNING, e, () -> "Cannot set up the k-NN plugin of OpenSearch in " + home);
        }
    }

    /**
     * A {@code ClusterService(Settings, ClusterSettings, ThreadPool)} over the built-in cluster
     * settings and the plugin's node settings (an index setting is refused by {@code ClusterSettings}).
     */
    private static Object clusterService(ClassLoader loader, Object state, Class<?> clusterService)
            throws ReflectiveOperationException {
        Class<?> settingsClass = Class.forName("org.opensearch.common.settings.Settings", true, loader);
        Class<?> settingClass = Class.forName("org.opensearch.common.settings.Setting", true, loader);
        Class<?> clusterSettingsClass = Class.forName("org.opensearch.common.settings.ClusterSettings", true, loader);
        Class<?> threadPoolClass = Class.forName("org.opensearch.threadpool.ThreadPool", true, loader);
        Class<?> executorBuilder = Class.forName("org.opensearch.threadpool.ExecutorBuilder", true, loader);

        Set<Object> registered = new HashSet<>((Set<?>)
                clusterSettingsClass.getField("BUILT_IN_CLUSTER_SETTINGS").get(null));
        for (Object setting :
                (Iterable<?>) state.getClass().getMethod("getSettings").invoke(state)) {
            if ((boolean) settingClass.getMethod("hasNodeScope").invoke(setting)) {
                registered.add(setting);
            }
        }
        Object builder = settingsClass.getMethod("builder").invoke(null);
        builder.getClass().getMethod("put", String.class, String.class).invoke(builder, "node.name", "leia");
        Object settings = builder.getClass().getMethod("build").invoke(builder);
        Object clusterSettings =
                clusterSettingsClass.getConstructor(settingsClass, Set.class).newInstance(settings, registered);
        // The pool's threads are daemons; it is made once per server and lives as long as the JVM.
        Object threadPool = threadPoolClass
                .getConstructor(settingsClass, executorBuilder.arrayType())
                .newInstance(settings, Array.newInstance(executorBuilder, 0));
        return clusterService
                .getConstructor(settingsClass, clusterSettingsClass, threadPoolClass)
                .newInstance(settings, clusterSettings, threadPool);
    }
}
