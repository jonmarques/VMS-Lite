package br.com.jonmarques.vmslite.playback;

import java.util.concurrent.ThreadLocalRandom;

final class ReconnectDelay {
    private ReconnectDelay() {}
    static long millis(int attempts) { return millis(attempts, ThreadLocalRandom.current().nextDouble()); }
    static long millis(int attempts, double jitter) {
        long base = Math.min(60_000L, 5_000L << Math.min(4, Math.max(0, attempts)));
        return base == 60_000L ? 54_000L + (long) (6_000L * jitter)
                : base + (long) (base * 0.2 * jitter);
    }
}
