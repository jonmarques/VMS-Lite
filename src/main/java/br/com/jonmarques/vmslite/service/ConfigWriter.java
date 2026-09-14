package br.com.jonmarques.vmslite.service;

import br.com.jonmarques.vmslite.entity.VMSConfig;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Serializes detached snapshots and coalesces rapid edits. */
public final class ConfigWriter implements AutoCloseable {
    private final ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, task -> {
        Thread thread = new Thread(task, "VMSLite-config");
        thread.setDaemon(true);
        return thread;
    });
    private final Consumer<Exception> onError;
    private ScheduledFuture<?> pending;

    public ConfigWriter(Consumer<Exception> onError) {
        this.onError = onError;
        executor.setRemoveOnCancelPolicy(true);
    }

    public synchronized void save(VMSConfig snapshot) {
        if (executor.isShutdown()) return;
        if (pending != null) pending.cancel(false);
        pending = executor.schedule(() -> {
            try { ConfigService.save(snapshot); }
            catch (Exception e) { onError.accept(e); }
        }, 300, TimeUnit.MILLISECONDS);
    }

    @Override public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                onError.accept(new IllegalStateException("Tempo limite ao salvar configuracao"));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
