package br.com.jonmarques.vmslite.ui;

import java.awt.*;
import javax.swing.*;

public final class TourHoverControlsChecks {
    public static void main(String[] args) throws Exception {
        Rectangle video = new Rectangle(100, 100, 640, 480);
        Rectangle overlay = TourHoverControls.hoveredBounds(video, new Point(120, 120));
        if (overlay == null || !video.contains(overlay)) throw new AssertionError("Controls must overlay video");
        if (TourHoverControls.hoveredBounds(video, overlay.getLocation()) == null)
            throw new AssertionError("Controls must stay available under pointer");
        if (TourHoverControls.hoveredBounds(video, new Point(99, 100)) != null
                || TourHoverControls.hoveredBounds(video, null) != null)
            throw new AssertionError("Hide outside video");
        Rectangle small = new Rectangle(-500, 20, 150, 80);
        if (!small.contains(TourHoverControls.hoveredBounds(small, new Point(-490, 30))))
            throw new AssertionError("Fit small video and negative monitor coordinates");
        SwingUtilities.invokeAndWait(() -> {
            JFrame owner = new JFrame();
            JPanel target = new JPanel();
            target.setBounds(video);
            TourHoverControls controls = new TourHoverControls(owner, new JPanel());
            JWindow window = (JWindow) owner.getOwnedWindows()[0];
            try {
                controls.setTarget(target);
                controls.update(new Point(120, 120));
                if (window.isVisible()) throw new AssertionError("Do not show over hidden/inactive owner");
                if (!target.getBounds().equals(video)) throw new AssertionError("Never resize video");
                controls.setTarget(null);
                if (window.isVisible()) throw new AssertionError("Hide when tour target removed");
            } finally { controls.dispose(); owner.dispose(); }
            if (window.isDisplayable()) throw new AssertionError("Release overlay window");
        });
        System.out.println("OK: hover bounds, pointer exit, small tiles, inactive owner, unchanged video, disposal");
    }
}
