package br.com.jonmarques.vmslite.playback;

import br.com.jonmarques.vmslite.entity.Camera;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Owns native playback. VLC calls are serialized per camera, outside Swing and VLC callbacks. */
public final class CameraPlayback {
    public enum State { LOADING, PLAYING, RECONNECTING }

    private final Camera camera;
    private final VideoPlayer player;
    private final Consumer<State> stateListener;
    private final Runnable refreshAddress;
    private final Object nativeLock = new Object();
    private volatile boolean closed;
    private boolean started;
    private boolean reconnecting;
    private boolean listening;
    private int attempts;
    private int frozenTicks;
    private long lastFrames = -1;
    private long generation;
    private ScheduledFuture<?> retry;
    private ScheduledFuture<?> watchdog;
    private Future<?> release;

    public CameraPlayback(Camera camera, VideoPlayer player,
                          Consumer<State> stateListener, Runnable refreshAddress) {
        this.camera = camera;
        this.player = player;
        this.stateListener = stateListener;
        this.refreshAddress = refreshAddress;
    }

    public void start() {
        execute(() -> {
            if (!listening) {
                player.listen(() -> execute(this::onPlaying), () -> execute(this::reconnect));
                listening = true;
            }
            started = true;
            cancelTasks();
            reconnecting = false;
            // Also retry a connection that never emits playing/error.
            reconnect();
            stateListener.accept(State.LOADING);
            play();
        });
    }

    private void execute(Runnable action) {
        if (!closed && !PlaybackExecutors.WORKERS.isShutdown()) {
            try {
                PlaybackExecutors.WORKERS.execute(() -> runGuarded(action));
            } catch (java.util.concurrent.RejectedExecutionException ignored) {
                // Shutdown can race with a final native callback.
            }
        }
    }

    private void runGuarded(Runnable action) {
        synchronized (nativeLock) {
            if (closed) return;
            try {
                action.run();
            } catch (RuntimeException e) {
                System.err.println("Falha no player de " + camera.getName() + ": " + e.getClass().getSimpleName());
                reconnect();
            }
        }
    }

    private void play() {
        generation++;
        player.play(camera.getUrl());
    }

    public void sample(Consumer<PlaybackSample> callback) {
        try {
            PlaybackExecutors.WORKERS.execute(() -> {
                PlaybackSample sample;
                synchronized (nativeLock) {
                    try {
                        sample = new PlaybackSample(System.nanoTime(), closed ? -1 : player.displayedFrames(),
                                closed ? -1 : player.mediaBytesRead(), generation);
                    } catch (RuntimeException unavailable) {
                        sample = new PlaybackSample(System.nanoTime(), -1, -1, generation);
                    }
                }
                callback.accept(sample);
            });
        } catch (java.util.concurrent.RejectedExecutionException shutdown) {
            callback.accept(new PlaybackSample(System.nanoTime(), -1, -1, -1));
        }
    }

    private void onPlaying() {
        if (!started) return;
        reconnecting = false;
        cancelTasks();
        lastFrames = -1;
        frozenTicks = 0;
        player.configureVideo();
        stateListener.accept(State.PLAYING);
        watchdog = PlaybackExecutors.WORKERS.scheduleWithFixedDelay(
                () -> runGuarded(this::checkFrames), 6, 6, TimeUnit.SECONDS);
    }

    private void reconnect() {
        if (closed || !started || reconnecting) return;
        reconnecting = true;
        attempts = 0;
        cancelTasks();
        stateListener.accept(State.RECONNECTING);
        retry = PlaybackExecutors.WORKERS.scheduleWithFixedDelay(
                () -> runGuarded(this::retry), 5, 5, TimeUnit.SECONDS);
    }

    private void retry() {
        if (!reconnecting) return;
        stateListener.accept(State.RECONNECTING);
        attempts++;
        if (attempts % 10 == 0) refreshAddress.run();
        if (attempts % 3 == 0) {
            player.prepare(camera.getUrl());
        }
        play();
    }

    private void checkFrames() {
        if (!player.isPlaying()) {
            reconnect();
            return;
        }
        long frames = player.displayedFrames();
        if (frames < 0) return;
        frozenTicks = frames == lastFrames ? frozenTicks + 1 : 0;
        lastFrames = frames;
        if (frozenTicks >= 3) reconnect();
    }

    private void cancelTasks() {
        if (retry != null) retry.cancel(false);
        if (watchdog != null) watchdog.cancel(false);
        retry = null;
        watchdog = null;
    }

    public synchronized Future<?> stop() {
        if (release != null) return release;
        closed = true;
        if (PlaybackExecutors.RELEASE.isShutdown()) return CompletableFuture.completedFuture(null);
        release = PlaybackExecutors.RELEASE.submit(() -> {
            synchronized (nativeLock) {
                cancelTasks();
                player.release();
            }
        });
        return release;
    }

    public boolean isClosed() { return closed; }
    public static boolean shutdownExecutors() { return PlaybackExecutors.shutdown(); }
}
