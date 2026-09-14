package br.com.jonmarques.vmslite.ui;

import javax.swing.*;

public final class HoverPointerChecks {
    public static void main(String[] args) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JFrame owner = new JFrame();
            Runnable first = HoverPointerTracker.subscribe(owner, point -> {});
            Runnable second = HoverPointerTracker.subscribe(owner, point -> {});
            Runnable third = HoverPointerTracker.subscribe(owner, point -> {});
            try {
                if (HoverPointerTracker.activeTrackers() != 1) throw new AssertionError("Overlays must share one timer");
                first.run(); second.run();
                if (HoverPointerTracker.activeTrackers() != 1) throw new AssertionError("Last overlay remains subscribed");
                third.run();
                if (HoverPointerTracker.activeTrackers() != 0) throw new AssertionError("Tracker lifecycle leak");
                System.out.println("OK: three overlays share one tracker; last disposal stops tracking");
            } finally { first.run(); second.run(); third.run(); owner.dispose(); }
        });
    }
}
