package br.com.jonmarques.vmslite.playback;

import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

final class PlaybackExecutors {
    static final ScheduledThreadPoolExecutor WORKERS = create(4, "video");
    static final ScheduledThreadPoolExecutor RELEASE = create(2, "release");

    private PlaybackExecutors() {}

    private static ScheduledThreadPoolExecutor create(int size, String name) {
        AtomicInteger sequence = new AtomicInteger();
        ThreadFactory factory = task -> {
            Thread thread = new Thread(task, "VMSLite-" + name + "-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(size, factory);
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        return executor;
    }

    static boolean shutdown() {
        WORKERS.shutdownNow();
        RELEASE.shutdown();
        try {
            return RELEASE.awaitTermination(10, TimeUnit.SECONDS)
                    && WORKERS.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
