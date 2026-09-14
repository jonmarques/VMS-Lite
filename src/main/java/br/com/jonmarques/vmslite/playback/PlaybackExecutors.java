package br.com.jonmarques.vmslite.playback;

import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

final class PlaybackExecutors {
    static final ScheduledThreadPoolExecutor TIMER = new ScheduledThreadPoolExecutor(1,
            task -> { Thread thread = new Thread(task, "VMSLite-video-timer"); thread.setDaemon(true); return thread; });
    private static final Set<ThreadPoolExecutor> CAMERAS = ConcurrentHashMap.newKeySet();
    private static final AtomicInteger IDS = new AtomicInteger();
    private static final AtomicBoolean RELEASE_FAILED = new AtomicBoolean();
    private static boolean shuttingDown;
    static {
        TIMER.setRemoveOnCancelPolicy(true);
        TIMER.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    }
    private PlaybackExecutors() {}

    static synchronized ThreadPoolExecutor cameraExecutor() {
        if (shuttingDown) throw new RejectedExecutionException("Playback shutdown");
        int id = IDS.incrementAndGet();
        ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(1), task -> {
                    Thread thread = new Thread(task, "VMSLite-camera-" + id);
                    thread.setDaemon(true);
                    return thread;
                }) {
            @Override protected void terminated() { CAMERAS.remove(this); }
        };
        executor.allowCoreThreadTimeOut(true);
        CAMERAS.add(executor);
        return executor;
    }

    static void releaseFailed() { RELEASE_FAILED.set(true); }

    static boolean shutdown() {
        synchronized (PlaybackExecutors.class) { shuttingDown = true; }
        TIMER.shutdownNow();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        for (ThreadPoolExecutor executor : CAMERAS) executor.shutdown();
        try {
            for (ThreadPoolExecutor executor : CAMERAS) {
                if (!executor.awaitTermination(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)) return false;
            }
            return !RELEASE_FAILED.get();
        } catch (InterruptedException error) { Thread.currentThread().interrupt(); return false; }
    }
}
