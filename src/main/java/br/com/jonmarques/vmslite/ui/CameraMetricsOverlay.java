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
    private final Timer timer;
    private CameraPanel target;
    private PlaybackSample previous;
    private String rates = "FPS: -- | Dados: --";
    private long nextSample;
    private long targetVersion;
    private boolean pending;
    private boolean closed;

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
        timer = new Timer(150, event -> update());
        timer.start();
    }

    private void update() {
        PointerInfo pointer = MouseInfo.getPointerInfo();
        CameraPanel hovered = null;
        if (owner.isActive() && owner.isShowing() && pointer != null) {
            for (CameraPanel camera : cameras.get()) {
                if (camera.isShowing() && new Rectangle(camera.getLocationOnScreen(), camera.getSize())
                        .contains(pointer.getLocation())) { hovered = camera; break; }
            }
        }
        if (target != hovered) {
            target = hovered;
            targetVersion++;
            previous = null;
            rates = "FPS: -- | Dados: --";
            nextSample = 0;
        }
        if (target == null || target.getWidth() < 120 || target.getHeight() < 65) {
            window.setVisible(false);
            return;
        }
        String connection = switch (target.getPlaybackState()) {
            case PLAYING -> "Conectada";
            case LOADING -> "Conectando";
            case RECONNECTING -> "Reconectando";
        };
        label.setText(connection + " | " + rates);
        Point origin = target.getLocationOnScreen();
        int height = 27;
        if (label.getPreferredSize().width > target.getWidth() - 12) {
            label.setText("<html>" + connection + "<br>" + rates.replace(" | ", "<br>") + "</html>");
            height = label.getPreferredSize().height;
        }
        if (target.getHeight() < height + 51) {
            window.setVisible(false);
            return;
        }
        window.setBounds(origin.x + 6, origin.y + 6,
                Math.min(label.getPreferredSize().width, target.getWidth() - 12), height);
        window.setVisible(true);
        long now = System.nanoTime();
        if (!pending && now >= nextSample) {
            pending = true;
            nextSample = now + 1_000_000_000L;
            long version = targetVersion;
            target.sample(sample -> SwingUtilities.invokeLater(() -> {
                pending = false;
                if (closed || version != targetVersion) return;
                rates = sample.ratesSince(previous);
                previous = sample;
            }));
        }
    }

    public void dispose() {
        closed = true;
        timer.stop();
        target = null;
        window.dispose();
    }
}
