package br.com.jonmarques.vmslite.playback;

public final class PlaybackSampleChecks {
    public static void main(String[] args) {
        PlaybackSample first = new PlaybackSample(1_000_000_000L, 100, 1000, 1);
        PlaybackSample next = new PlaybackSample(3_000_000_000L, 150, 501000, 1);
        check(next.ratesSince(first).equals("FPS: 25.0 | Dados: 2.00 Mbps"));
        check(next.ratesSince(null).equals("FPS: -- | Dados: --"));
        check(new PlaybackSample(4_000_000_000L, 0, 0, 2).ratesSince(next).equals("FPS: -- | Dados: --"));
        check(new PlaybackSample(4_000_000_000L, -1, -1, 1).ratesSince(next).equals("FPS: -- | Dados: --"));
        check(first.ratesSince(next).equals("FPS: -- | Dados: --"));
        System.out.println("OK: measured FPS/Mbps, warmup, reconnect, missing statistics and invalid time");
    }
    private static void check(boolean valid) { if (!valid) throw new AssertionError("Invalid metrics"); }
}
