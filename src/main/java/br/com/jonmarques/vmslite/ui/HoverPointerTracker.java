package br.com.jonmarques.vmslite.ui;

import java.awt.*;
import java.util.*;
import java.util.function.Consumer;
import javax.swing.JFrame;
import javax.swing.Timer;

/** All overlays of a window share one pointer poll. Accessed only on the EDT. */
final class HoverPointerTracker {
    private static final Map<JFrame, HoverPointerTracker> TRACKERS = new IdentityHashMap<>();
    private final java.util.List<Consumer<Point>> listeners = new ArrayList<>();
    private final Timer timer;

    private HoverPointerTracker(JFrame owner) {
        timer = new Timer(150, event -> {
            Point point = null;
            if (owner.isShowing() && owner.isActive()) {
                PointerInfo pointer = MouseInfo.getPointerInfo();
                if (pointer != null) point = pointer.getLocation();
            }
            for (Consumer<Point> listener : java.util.List.copyOf(listeners)) listener.accept(point);
        });
    }

    static Runnable subscribe(JFrame owner, Consumer<Point> listener) {
        HoverPointerTracker tracker = TRACKERS.computeIfAbsent(owner, HoverPointerTracker::new);
        tracker.listeners.add(listener);
        tracker.timer.start();
        return () -> {
            tracker.listeners.remove(listener);
            if (tracker.listeners.isEmpty()) { tracker.timer.stop(); TRACKERS.remove(owner, tracker); }
        };
    }

    static int activeTrackers() { return TRACKERS.size(); }
}
