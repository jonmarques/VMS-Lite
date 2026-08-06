package br.com.jonmarques.vmslite;

import uk.co.caprica.vlcj.factory.MediaPlayerFactory;
import uk.co.caprica.vlcj.player.component.EmbeddedMediaPlayerComponent;
import uk.co.caprica.vlcj.player.component.InputEvents;

public final class VlcManager {
    private static MediaPlayerFactory factory;

    private VlcManager() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            VlcManager.shutdown();
        }));
    }

    public static synchronized void init() {
        if (factory == null) {
            factory = new MediaPlayerFactory();
        }
    }

    public static EmbeddedMediaPlayerComponent createPlayer() {
        if (factory == null) {
            init();
        }
        return new EmbeddedMediaPlayerComponent(
                factory,
                null,                          // FullScreenStrategy
                null,                          // JWindow (overlay), não usado
                InputEvents.DISABLE_NATIVE,
                null                           // VideoSurfaceComponent customizado, deixa padrão
        );
    }

    public static synchronized void shutdown() {
        if (factory != null) {
            factory.release();
            factory = null;
        }
    }
}
