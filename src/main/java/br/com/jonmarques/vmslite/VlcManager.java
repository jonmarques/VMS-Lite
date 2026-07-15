package br.com.jonmarques.vmslite;

import uk.co.caprica.vlcj.factory.MediaPlayerFactory;
import uk.co.caprica.vlcj.player.component.CallbackMediaPlayerComponent;
import uk.co.caprica.vlcj.player.component.InputEvents;
import uk.co.caprica.vlcj.player.component.callback.CallbackImagePainter;
import uk.co.caprica.vlcj.player.embedded.fullscreen.FullScreenStrategy;

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

    public static CallbackMediaPlayerComponent createPlayer() {
        if (factory == null) {
            init();
        }
        return new CallbackMediaPlayerComponent(
                factory,
                (FullScreenStrategy) null,
                InputEvents.DISABLE_NATIVE,
                false,
                (CallbackImagePainter) null
        );
    }

    public static synchronized void shutdown() {
        if (factory != null) {
            factory.release();
            factory = null;
        }
    }
}
