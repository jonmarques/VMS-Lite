package br.com.jonmarques.vmslite.ui;

import java.awt.*;
import javax.swing.*;

/** An opaque owned window can cover native video without transparent glass-pane mixing. */
public final class FullscreenControls {
    private final JFrame owner;
    private final Runnable exitFullscreen;
    private Runnable unsubscribe;
    private JWindow window;

    public FullscreenControls(JFrame owner, Runnable exitFullscreen) {
        this.owner = owner;
        this.exitFullscreen = exitFullscreen;
    }

    public void setEnabled(boolean enabled) {
        dispose();
        if (!enabled) return;
        window = new JWindow(owner, owner.getGraphicsConfiguration());
        window.setFocusableWindowState(false);
        window.setBackground(new Color(220, 53, 69));
        JButton button = new JButton("Sair da tela cheia");
        button.setFocusable(false);
        button.setOpaque(true);
        button.setBackground(new Color(220, 53, 69));
        button.setForeground(Color.WHITE);
        button.putClientProperty("FlatLaf.style",
                "arc: 0; background: #dc3545; foreground: #ffffff; "
                + "hoverBackground: #b52a38; hoverForeground: #ffffff; "
                + "pressedBackground: #97232e; pressedForeground: #ffffff");
        button.addActionListener(event -> exitFullscreen.run());
        window.setContentPane(button);
        window.pack();
        window.setSize(Math.max(170, window.getWidth()), Math.max(38, window.getHeight()));
        unsubscribe = HoverPointerTracker.subscribe(owner, this::update);
    }

    public void update(Point screenPoint) {
        if (window == null) return;
        Rectangle bounds = owner.getBounds();
        int x = bounds.x + bounds.width - window.getWidth() - 20;
        int y = bounds.y + 15;
        boolean nearButton = screenPoint != null && bounds.contains(screenPoint)
                && screenPoint.y < y + window.getHeight() + 25
                && screenPoint.x >= x - 40;
        boolean visible = owner.isActive() && owner.isShowing() && nearButton;
        if (visible && (window.getX() != x || window.getY() != y)) window.setLocation(x, y);
        if (window.isVisible() != visible) window.setVisible(visible);
    }

    public void dispose() {
        if (unsubscribe != null) { unsubscribe.run(); unsubscribe = null; }
        if (window != null) {
            window.dispose();
            window = null;
        }
    }
}
