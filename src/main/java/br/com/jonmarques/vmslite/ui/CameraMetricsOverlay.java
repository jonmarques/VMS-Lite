package br.com.jonmarques.vmslite.ui;

import br.com.jonmarques.vmslite.CameraPanel;
import br.com.jonmarques.vmslite.playback.PlaybackSample;
import java.awt.*;
import java.util.List;
import java.util.function.Supplier;
import javax.swing.*;

/** One shared hover window and at most one outstanding statistics request. */
public final class CameraMetricsOverlay {
    private final JFrame owner;
    private final Supplier<List<CameraPanel>> cameras;
    private final JWindow window;
    private final JLabel label = new JLabel();
    private final Runnable unsubscribe;
    private CameraPanel target;
    private PlaybackSample previous;
    private String rates = "FPS: -- | Dados: --";
    private long nextSample;
    private long targetVersion;
    private boolean pending;
    private boolean closed;
    private String renderedText;
    private int renderedWidth = -1;
    private int labelHeight = 27;

    public CameraMetricsOverlay(JFrame owner, Supplier<List<CameraPanel>> cameras) {
        this.owner = owner;
        this.cameras = cameras;
        window = new JWindow(owner);
        window.setFocusableWindowState(false);
        label.setOpaque(true);
        label.setBackground(new Color(30, 30, 30));
        label.setForeground(Color.WHITE);
        label.setBorder(BorderFactory.createEmptyBorder(4, 7, 4, 7));
        window.setContentPane(label);
        unsubscribe = HoverPointerTracker.subscribe(owner, this::update);
    }

    private void update(Point pointer) {
        CameraPanel hovered = null;
        if (owner.isActive() && owner.isShowing() && pointer != null) {
            for (CameraPanel camera : cameras.get()) {
                if (camera.isShowing() && new Rectangle(camera.getLocationOnScreen(), camera.getSize())
                        .contains(pointer)) { hovered = camera; break; }
            }
        }
        if (target != hovered) {
            target = hovered;
            targetVersion++;
            previous = null;
            rates = "FPS: -- | Dados: --";
            nextSample = 0;
            pending = false;
        }
        if (target == null || target.getWidth() < 120 || target.getHeight() < 65) {
            if (window.isVisible()) window.setVisible(false);
            return;
        }
        String connection = switch (target.getPlaybackState()) {
            case PLAYING -> "Conectada";
            case LOADING -> "Conectando";
            case RECONNECTING -> "Reconectando";
        };
        String text = connection + " | " + rates;
        Point origin = target.getLocationOnScreen();
        if (!text.equals(renderedText) || renderedWidth != target.getWidth()) {
            renderedText = text;
            renderedWidth = target.getWidth();
            label.setText(text);
            labelHeight = 27;
            if (label.getPreferredSize().width > renderedWidth - 12) {
                label.setText("<html>" + connection + "<br>" + rates.replace(" | ", "<br>") + "</html>");
                labelHeight = label.getPreferredSize().height;
            }
        }
        if (target.getHeight() < labelHeight + 51) {
            if (window.isVisible()) window.setVisible(false);
            return;
        }
        Rectangle bounds = new Rectangle(origin.x + 6, origin.y + 6,
                Math.min(label.getPreferredSize().width, target.getWidth() - 12), labelHeight);
        if (!bounds.equals(window.getBounds())) window.setBounds(bounds);
        if (!window.isVisible()) window.setVisible(true);
        long now = System.nanoTime();
        if (!pending && now >= nextSample) {
            pending = true;
            nextSample = now + 1_000_000_000L;
            long version = targetVersion;
            target.sample(sample -> SwingUtilities.invokeLater(() -> {
                if (closed || version != targetVersion) return;
                pending = false;
                rates = sample.ratesSince(previous);
                previous = sample;
            }));
        }
    }

    public void dispose() {
        closed = true;
        unsubscribe.run();
        target = null;
        window.dispose();
    }
}
