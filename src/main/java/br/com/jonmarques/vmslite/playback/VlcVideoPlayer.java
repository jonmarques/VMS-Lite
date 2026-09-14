package br.com.jonmarques.vmslite.playback;

import uk.co.caprica.vlcj.media.MediaStatistics;
import uk.co.caprica.vlcj.player.base.MediaPlayer;
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter;
import uk.co.caprica.vlcj.player.component.EmbeddedMediaPlayerComponent;

public final class VlcVideoPlayer implements VideoPlayer {
    private final EmbeddedMediaPlayerComponent component;
    private MediaPlayerEventAdapter listener;

    public VlcVideoPlayer(EmbeddedMediaPlayerComponent component) { this.component = component; }

    @Override public void listen(Runnable playing, Runnable disconnected) {
        listener = new MediaPlayerEventAdapter() {
            @Override public void playing(MediaPlayer player) { playing.run(); }
            @Override public void error(MediaPlayer player) { disconnected.run(); }
            @Override public void stopped(MediaPlayer player) { disconnected.run(); }
            @Override public void finished(MediaPlayer player) { disconnected.run(); }
            @Override public void videoOutput(MediaPlayer player, int count) {
                if (count == 0) disconnected.run();
            }
        };
        component.mediaPlayer().events().addMediaPlayerEventListener(listener);
    }

    @Override public void play(String url) { component.mediaPlayer().media().play(url, MediaOptions.values()); }
    @Override public void prepare(String url) { component.mediaPlayer().media().prepare(url, MediaOptions.values()); }
    @Override public void configureVideo() {
        component.mediaPlayer().audio().setMute(true);
        component.mediaPlayer().video().setAspectRatio(null);
        component.mediaPlayer().video().setScale(0);
    }
    @Override public boolean isPlaying() { return component.mediaPlayer().status().isPlaying(); }
    @Override public long displayedFrames() {
        MediaStatistics statistics = component.mediaPlayer().media().info().statistics();
        return statistics == null ? -1 : statistics.picturesDisplayed();
    }
    @Override public void release() {
        try {
            if (listener != null) component.mediaPlayer().events().removeMediaPlayerEventListener(listener);
        } finally {
            component.release();
        }
    }

    @Override public long mediaBytesRead() {
        MediaStatistics statistics = component.mediaPlayer().media().info().statistics();
        return statistics == null ? -1 : Integer.toUnsignedLong(statistics.demuxBytesRead());
    }
}
