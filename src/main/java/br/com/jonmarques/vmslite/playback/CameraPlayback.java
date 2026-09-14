package br.com.jonmarques.vmslite.playback;

import br.com.jonmarques.vmslite.entity.Camera;
import java.util.concurrent.*;
import java.util.function.Consumer;
import static br.com.jonmarques.vmslite.playback.CameraCommandQueue.Command.*;

/** Native access is confined to this camera's queue, never a shared timer or the EDT. */
public final class CameraPlayback {
    public enum State { LOADING, PLAYING, RECONNECTING }
    private final Camera camera;
    private final VideoPlayer player;
    private final Consumer<State> stateListener;
    private final Runnable refreshAddress;
    private final CameraCommandQueue commands = new CameraCommandQueue();
    private volatile boolean closed;
    private boolean started;
    private boolean reconnecting;
    private boolean listening;
    private int attempts;
    private int frozenTicks;
    private long lastFrames = -1;
    private long generation;
    private long timerVersion;
    private long nextAddressRefresh;
    private ScheduledFuture<?> retry;
    private ScheduledFuture<?> watchdog;
    private Future<?> release;
    private CompletableFuture<PlaybackSample> sampling;

    public CameraPlayback(Camera camera, VideoPlayer player, Consumer<State> stateListener, Runnable refreshAddress) {
        this.camera = camera;
        this.player = player;
        this.stateListener = stateListener;
        this.refreshAddress = refreshAddress;
    }

    public void start() {
        execute(START, () -> {
            if (!listening) {
                player.listen(() -> execute(PLAYING, this::onPlaying), () -> execute(ERROR, this::reconnect));
                listening = true;
            }
            started = true;
            cancelTasks();
            reconnecting = false;
            reconnect();
            stateListener.accept(State.LOADING);
            play();
        });
    }

    private void execute(CameraCommandQueue.Command key, Runnable action) {
        if (closed) return;
        commands.submit(key, () -> {
            if (closed) return;
            try { action.run(); }
            catch (RuntimeException error) {
                System.err.println("Falha no player: " + error.getClass().getSimpleName());
                reconnect();
            }
        });
    }

    private void play() { generation++; player.play(camera.getUrl()); }

    public synchronized void sample(Consumer<PlaybackSample> callback) {
        PlaybackSample unavailable = new PlaybackSample(System.nanoTime(), -1, -1, -1);
        if (closed) { callback.accept(unavailable); return; }
        if (sampling == null) {
            CompletableFuture<PlaybackSample> future = new CompletableFuture<>();
            sampling = future;
            future.completeOnTimeout(unavailable, 3, TimeUnit.SECONDS);
            commands.submit(SAMPLE, () -> {
                try {
                    future.complete(closed ? unavailable : new PlaybackSample(System.nanoTime(),
                            player.displayedFrames(), player.mediaBytesRead(), generation));
                } catch (RuntimeException error) { future.complete(unavailable); }
                finally { synchronized (CameraPlayback.this) { if (sampling == future) sampling = null; } }
            });
        }
        sampling.thenAccept(callback);
    }

    private void onPlaying() {
        if (!started) return;
        reconnecting = false;
        attempts = 0;
        cancelTasks();
        lastFrames = -1;
        frozenTicks = 0;
        player.configureVideo();
        stateListener.accept(State.PLAYING);
        long version = timerVersion;
        watchdog = PlaybackExecutors.TIMER.scheduleWithFixedDelay(
                () -> execute(CHECK, () -> { if (version == timerVersion) checkFrames(); }), 6, 6, TimeUnit.SECONDS);
    }

    private void reconnect() {
        if (closed || !started || reconnecting) return;
        reconnecting = true;
        attempts = 0;
        cancelTasks();
        nextAddressRefresh = System.nanoTime() + TimeUnit.SECONDS.toNanos(50);
        stateListener.accept(State.RECONNECTING);
        scheduleRetry();
    }

    private void scheduleRetry() {
        if (closed || !reconnecting || PlaybackExecutors.TIMER.isShutdown()) return;
        long version = timerVersion;
        retry = PlaybackExecutors.TIMER.schedule(
                () -> execute(RETRY, () -> { if (version == timerVersion) retry(); }),
                ReconnectDelay.millis(attempts), TimeUnit.MILLISECONDS);
    }

    private void retry() {
        if (!reconnecting) return;
        stateListener.accept(State.RECONNECTING);
        if (attempts < Integer.MAX_VALUE) attempts++;
        try {
            if (System.nanoTime() >= nextAddressRefresh) {
                nextAddressRefresh = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
                refreshAddress.run();
            }
            if (attempts % 3 == 0) player.prepare(camera.getUrl());
            play();
        } finally { scheduleRetry(); }
    }

    private void checkFrames() {
        if (!player.isPlaying()) { reconnect(); return; }
        long frames = player.displayedFrames();
        if (frames < 0) return;
        frozenTicks = frames == lastFrames ? frozenTicks + 1 : 0;
        lastFrames = frames;
        if (frozenTicks >= 3) reconnect();
    }

    private void cancelTasks() {
        timerVersion++;
        if (retry != null) retry.cancel(false);
        if (watchdog != null) watchdog.cancel(false);
        retry = null;
        watchdog = null;
    }

    public synchronized Future<?> stop() {
        if (release != null) return release;
        closed = true;
        if (sampling != null) sampling.complete(new PlaybackSample(System.nanoTime(), -1, -1, -1));
        release = commands.close(() -> { cancelTasks(); player.release(); });
        return release;
    }

    public boolean isClosed() { return closed; }
    public static boolean shutdownExecutors() { return PlaybackExecutors.shutdown(); }
}
