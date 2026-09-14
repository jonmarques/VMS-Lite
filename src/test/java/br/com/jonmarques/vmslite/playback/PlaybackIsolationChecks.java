package br.com.jonmarques.vmslite.playback;

import br.com.jonmarques.vmslite.entity.Camera;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class PlaybackIsolationChecks {
    public static void main(String[] args) throws Exception {
        CountDownLatch unblock = new CountDownLatch(1), entered = new CountDownLatch(1), healthyPlaying = new CountDownLatch(1);
        FakePlayer blocked = new FakePlayer() {
            @Override public void play(String url) {
                calls.incrementAndGet();
                entered.countDown();
                try { unblock.await(); } catch (InterruptedException e) { throw new AssertionError(e); }
            }
        };
        FakePlayer healthy = new FakePlayer();
        CameraPlayback first = new CameraPlayback(new Camera("blocked", "rtsp://host/1", null), blocked, state -> {}, () -> {});
        CameraPlayback second = new CameraPlayback(new Camera("healthy", "rtsp://host/2", null), healthy,
                state -> { if (state == CameraPlayback.State.PLAYING) healthyPlaying.countDown(); }, () -> {});
        try {
            first.start();
            check(entered.await(2, TimeUnit.SECONDS), "Native call entered");
            for (int i = 0; i < 5000; i++) first.start();
            var unavailable = new CompletableFuture<PlaybackSample>();
            first.sample(unavailable::complete);
            second.start();
            check(healthyPlaying.await(2, TimeUnit.SECONDS), "Blocked camera must not starve another camera");
            var timer = new CountDownLatch(1);
            PlaybackExecutors.TIMER.schedule(timer::countDown, 10, TimeUnit.MILLISECONDS);
            check(timer.await(1, TimeUnit.SECONDS), "Native call must not block timer");
            check(unavailable.get(4, TimeUnit.SECONDS).frames() == -1, "Blocked metrics expire");
            Future<?> release = first.stop();
            second.stop().get(2, TimeUnit.SECONDS);
            check(!release.isDone(), "Never release concurrently with native call");
            unblock.countDown();
            release.get(2, TimeUnit.SECONDS);
            check(blocked.calls.get() == 1 && blocked.releases.get() == 1, "Pending starts coalesced/discarded on stop");
            for (int attempt = 0; attempt < 20; attempt++) {
                long low = ReconnectDelay.millis(attempt, 0), high = ReconnectDelay.millis(attempt, 0.99);
                check(low >= 5000 && low <= high && high <= 60000, "Reconnect bounds");
                if (attempt > 0) check(low >= ReconnectDelay.millis(attempt - 1, 0), "Progressive retry");
            }
            check(ReconnectDelay.millis(0, 0) == 5000, "Initial retry resets to five seconds");
            System.out.println("OK: native isolation, 5000 coalesced starts, metrics deadline, independent release, backoff bounds");
        } finally {
            unblock.countDown();
            first.stop().get(3, TimeUnit.SECONDS);
            second.stop().get(3, TimeUnit.SECONDS);
            check(CameraPlayback.shutdownExecutors(), "Clean playback shutdown");
        }
    }
    private static class FakePlayer implements VideoPlayer {
        Runnable playing;
        final AtomicInteger calls = new AtomicInteger(), releases = new AtomicInteger();
        public void listen(Runnable playing, Runnable disconnected) { this.playing = playing; }
        public void play(String url) { calls.incrementAndGet(); playing.run(); }
        public void prepare(String url) {}
        public void configureVideo() {}
        public boolean isPlaying() { return true; }
        public long displayedFrames() { return 5; }
        public void release() { releases.incrementAndGet(); }
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
