package br.com.jonmarques.vmslite.playback;

import br.com.jonmarques.vmslite.entity.Camera;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class PlaybackChecks {
    public static void main(String[] args) throws Exception {
        FakePlayer fake = new FakePlayer();
        CountDownLatch playing = new CountDownLatch(1);
        CameraPlayback playback = new CameraPlayback(new Camera("test", "rtsp://host/live", null), fake,
                state -> { if (state == CameraPlayback.State.PLAYING) playing.countDown(); }, () -> {});
        try {
            playback.start();
            if (!playing.await(3, TimeUnit.SECONDS)) throw new AssertionError("Playing event not delivered");
            var sample = new java.util.concurrent.CompletableFuture<PlaybackSample>();
            playback.sample(sample::complete);
            if (sample.get(3, TimeUnit.SECONDS).frames() != 10) throw new AssertionError("Sample active player");
            for (int i = 0; i < 25; i++) playback.start();
            Future<?> first = playback.stop();
            if (first != playback.stop()) throw new AssertionError("Release must be idempotent");
            first.get(3, TimeUnit.SECONDS);
            var closedSample = new java.util.concurrent.CompletableFuture<PlaybackSample>();
            playback.sample(closedSample::complete);
            if (closedSample.get(3, TimeUnit.SECONDS).frames() != -1) throw new AssertionError("Sample closed player");
            playback.start();
            if (fake.releases.get() != 1 || fake.callsAfterRelease.get() != 0) {
                throw new AssertionError("Native resource lifecycle");
            }
            if (fake.callsAfterRelease.get() != 0) throw new AssertionError("Callback after release");
            System.out.println("OK: playback callbacks, repeated start, idempotent stop, no access after release");
        } finally {
            playback.stop().get(3, TimeUnit.SECONDS);
            CameraPlayback.shutdownExecutors();
        }
    }

    private static final class FakePlayer implements VideoPlayer {
        private Runnable playing;
        private final AtomicInteger releases = new AtomicInteger();
        private final AtomicInteger callsAfterRelease = new AtomicInteger();
        private void checkOpen() { if (releases.get() > 0) callsAfterRelease.incrementAndGet(); }
        @Override public void listen(Runnable playing, Runnable disconnected) { checkOpen(); this.playing = playing; }
        @Override public void play(String url) { checkOpen(); playing.run(); }
        @Override public void prepare(String url) { checkOpen(); }
        @Override public void configureVideo() { checkOpen(); }
        @Override public boolean isPlaying() { checkOpen(); return true; }
        @Override public long displayedFrames() { checkOpen(); return 10; }
        @Override public void release() { releases.incrementAndGet(); }
    }
}
