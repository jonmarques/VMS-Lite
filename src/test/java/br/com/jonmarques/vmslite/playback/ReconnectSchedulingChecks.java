package br.com.jonmarques.vmslite.playback;

import br.com.jonmarques.vmslite.entity.Camera;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public final class ReconnectSchedulingChecks {
    public static void main(String[] args) throws Exception {
        CountDownLatch firstRecovery = new CountDownLatch(1), secondRecovery = new CountDownLatch(1);
        AtomicInteger plays = new AtomicInteger();
        AtomicLong firstPlay = new AtomicLong(), secondPlay = new AtomicLong();
        Runnable[] events = new Runnable[2];
        VideoPlayer fake = new VideoPlayer() {
            public void listen(Runnable playing, Runnable disconnected) { events[0] = playing; events[1] = disconnected; }
            public void play(String url) {
                int count = plays.incrementAndGet();
                if (count == 1) { firstPlay.set(System.nanoTime()); throw new IllegalStateException("simulated connection failure"); }
                if (count == 2) secondPlay.set(System.nanoTime());
                events[0].run();
            }
            public void prepare(String url) {}
            public void configureVideo() {}
            public boolean isPlaying() { return true; }
            public long displayedFrames() { return 100; }
            public void release() {}
        };
        CameraPlayback playback = new CameraPlayback(new Camera("retry", "rtsp://host/live", null), fake, state -> {
            if (state == CameraPlayback.State.PLAYING) {
                if (plays.get() == 2) firstRecovery.countDown();
                if (plays.get() >= 3) secondRecovery.countDown();
            }
        }, () -> {});
        try {
            playback.start();
            if (!firstRecovery.await(8, TimeUnit.SECONDS)) throw new AssertionError("Failed initial connection did not retry");
            if (secondPlay.get() - firstPlay.get() < TimeUnit.SECONDS.toNanos(5)) throw new AssertionError("Busy retry loop");
            for (int i = 0; i < 100; i++) events[1].run();
            if (!secondRecovery.await(8, TimeUnit.SECONDS)) throw new AssertionError("Successful playback did not reset backoff");
            if (plays.get() != 3) throw new AssertionError("Duplicate error callbacks created extra retries");
            System.out.println("OK: native failure retries, successful playback resets delay, duplicate errors stay coalesced");
        } finally { playback.stop().get(3, TimeUnit.SECONDS); CameraPlayback.shutdownExecutors(); }
    }
}
