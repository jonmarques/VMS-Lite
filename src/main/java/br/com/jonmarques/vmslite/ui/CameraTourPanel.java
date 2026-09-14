package br.com.jonmarques.vmslite.ui;

import br.com.jonmarques.vmslite.VMSLite;
import br.com.jonmarques.vmslite.entity.Camera;
import br.com.jonmarques.vmslite.entity.CameraTourConfig;
import br.com.jonmarques.vmslite.playback.CameraTourController;
import java.awt.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import javax.swing.*;

/** Controls only; video surfaces stay attached to the grid. */
public final class CameraTourPanel extends JPanel {
    private static final long serialVersionUID = 1L;
    private final CameraTourController controller;
    private final JLabel title = new JLabel("Tour");
    private final TourHoverControls hoverControls;
    private boolean closed;

    public CameraTourPanel(VMSLite owner) {
        super(new BorderLayout(8, 0));
        setVisible(false);
        JPanel toolbar = new JPanel(new BorderLayout(8, 0));
        toolbar.setOpaque(true);
        toolbar.setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 3));
        controller = new CameraTourController(camera -> {
            title.setText(camera == null ? "Tour" : "Tour: " + camera.getName());
            title.setToolTipText(camera == null ? null : camera.getName());
            owner.rebuildLayout();
        });
        toolbar.add(title, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 3, 0));
        JToggleButton pause = new JToggleButton("\u23F8");
        JButton next = new JButton("\u23ED");
        pause.setPreferredSize(new Dimension(32, 27));
        next.setPreferredSize(new Dimension(32, 27));
        pause.setToolTipText("Pausar tour");
        next.setToolTipText("Proxima camera");
        pause.addActionListener(event -> {
            controller.setPaused(pause.isSelected());
            pause.setText(pause.isSelected() ? "\u25B6" : "\u23F8");
            pause.setToolTipText(pause.isSelected() ? "Retomar tour" : "Pausar tour");
        });
        next.addActionListener(event -> controller.next());
        buttons.add(pause);
        buttons.add(next);
        toolbar.add(buttons, BorderLayout.EAST);
        hoverControls = new TourHoverControls(owner, toolbar);
    }

    public void setTarget(Component target) { if (!closed) hoverControls.setTarget(target); }

    public Camera getCurrent() { return controller.getCurrent(); }
    public boolean isPaused() { return controller.isPaused(); }
    public void setPaused(boolean paused) { if (!closed) controller.setPaused(paused); }
    public void configure(CameraTourConfig config, List<Camera> cameras) {
        if (!closed) controller.configure(config, cameras);
    }
    public Future<?> stop() {
        closed = true;
        controller.close();
        hoverControls.dispose();
        return CompletableFuture.completedFuture(null);
    }
}
