package br.com.jonmarques.vmslite.playback;

public record PlaybackSample(long nanos, long frames, long bytes, long generation) {
    public String ratesSince(PlaybackSample previous) {
        if (previous == null || generation != previous.generation || nanos <= previous.nanos)
            return "FPS: -- | Dados: --";
        double seconds = (nanos - previous.nanos) / 1_000_000_000.0;
        String fps = frames < 0 || previous.frames < 0 || frames < previous.frames ? "--"
                : String.format(java.util.Locale.ROOT, "%.1f", (frames - previous.frames) / seconds);
        String rate = bytes < 0 || previous.bytes < 0 || bytes < previous.bytes ? "--"
                : String.format(java.util.Locale.ROOT, "%.2f Mbps", (bytes - previous.bytes) * 8.0 / seconds / 1_000_000);
        return "FPS: " + fps + " | Dados: " + rate;
    }
}
