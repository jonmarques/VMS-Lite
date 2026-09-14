package br.com.jonmarques.vmslite.playback;

import java.util.LinkedHashMap;
import java.util.concurrent.*;

/** One native worker per camera; pending operations are bounded and coalesced by purpose. */
final class CameraCommandQueue {
    enum Command { START, PLAYING, ERROR, RETRY, CHECK, SAMPLE }
    private final ThreadPoolExecutor worker = PlaybackExecutors.cameraExecutor();
    private final LinkedHashMap<Command, Runnable> pending = new LinkedHashMap<>();
    private final CompletableFuture<Void> released = new CompletableFuture<>();
    private boolean draining;
    private boolean closed;
    private Runnable release;

    synchronized boolean submit(Command key, Runnable action) {
        if (closed) return false;
        pending.put(key, action);
        startDrain();
        return true;
    }

    synchronized Future<?> close(Runnable action) {
        if (closed) return released;
        closed = true;
        pending.clear();
        release = action;
        startDrain();
        return released;
    }

    private void startDrain() {
        if (draining) return;
        draining = true;
        worker.execute(this::drain);
    }

    private void drain() {
        while (true) {
            Runnable action;
            boolean releasing;
            synchronized (this) {
                releasing = closed;
                if (releasing) { action = release; release = null; }
                else if (!pending.isEmpty()) {
                    var iterator = pending.entrySet().iterator();
                    action = iterator.next().getValue();
                    iterator.remove();
                } else { draining = false; return; }
            }
            try {
                action.run();
                if (releasing) released.complete(null);
            } catch (Throwable error) {
                if (releasing) { PlaybackExecutors.releaseFailed(); released.completeExceptionally(error); }
                else System.err.println("Falha em tarefa de video: " + error.getClass().getSimpleName());
            } finally {
                if (releasing) worker.shutdown();
            }
            if (releasing) return;
        }
    }
}
