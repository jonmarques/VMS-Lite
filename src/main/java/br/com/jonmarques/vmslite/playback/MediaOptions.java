package br.com.jonmarques.vmslite.playback;

final class MediaOptions {
    private MediaOptions() {}
    private static final String[] OPTIONS = new String[]{
            ":network-caching=1000",      // Mantém o buffer fixo em 1 segundo
            ":live-caching=1000",

            ":clock-synchro=1",
            ":clock-jitter=500",

            ":framedrop",                // Permite descartar quadros da fila se acumular atraso
            ":drop-late-frames",         // Se o vídeo ficar >1s atrás do tempo real, pula para o I-Frame atual

            ":avcodec-hw=any",           // Tenta aceleração por hardware
            ":avcodec-fast",
            ":avcodec-threads=2",
            ":avcodec-skiploopfilter=0",

            ":audio-track-id=-1",
            ":no-audio-time-sync",

            ":rtsp-tcp",
            ":no-video-title-show",
            ":rtsp-timeout=5",
            ":network-timeout=5000"
    };

    static String[] values() { return OPTIONS.clone(); }
}
