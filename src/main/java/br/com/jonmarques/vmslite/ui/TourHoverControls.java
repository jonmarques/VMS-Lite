package br.com.jonmarques.vmslite.ui;

import java.awt.*;
import javax.swing.*;

/** An owned window overlays heavyweight VLC video without changing its bounds. */
public final class TourHoverControls {
    private final JFrame owner;
    private final JWindow window;
    private final Timer timer;
    private Component target;

    public TourHoverControls(JFrame owner, JPanel content) {
        this.owner = owner;
        window = new JWindow(owner);
        window.setFocusableWindowState(false);
        window.setContentPane(content);
        timer = new Timer(150, event -> {
            PointerInfo pointer = MouseInfo.getPointerInfo();
            update(pointer == null ? null : pointer.getLocation());
        });
    }

    public void setTarget(Component target) {
        if (this.target != target) window.setVisible(false);
        this.target = target;
        if (target == null) { timer.stop(); window.setVisible(false); }
        else if (!timer.isRunning()) timer.start();
    }

    void update(Point pointer) {
        boolean visible = pointer != null && target != null && target.isShowing()
                && owner.isActive() && owner.isShowing();
        if (visible) {
            Rectangle video = new Rectangle(target.getLocationOnScreen(), target.getSize());
            Rectangle overlay = hoveredBounds(video, pointer);
            visible = overlay != null;
            if (visible) window.setBounds(overlay);
        }
        if (window.isVisible() != visible) window.setVisible(visible);
    }

    static Rectangle hoveredBounds(Rectangle video, Point pointer) {
        if (pointer == null || !video.contains(pointer) || video.width < 100 || video.height < 45) return null;
        int width = Math.min(320, video.width - 12);
        return new Rectangle(video.x + video.width - width - 6, video.y + video.height - 39, width, 33);
    }

    public void dispose() {
        timer.stop();
        target = null;
        window.dispose();
    }
}
