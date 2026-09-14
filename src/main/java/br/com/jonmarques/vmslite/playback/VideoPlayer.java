package br.com.jonmarques.vmslite.playback;

/** Narrow boundary around native VLC operations, also usable by lifecycle tests. */
public interface VideoPlayer {
    void listen(Runnable playing, Runnable disconnected);
    void play(String url);
    void prepare(String url);
    void configureVideo();
    boolean isPlaying();
    long displayedFrames();
    default long mediaBytesRead() { return -1; }
    void release();
}
