package br.com.jonmarques.vmslite.ui;

import br.com.jonmarques.vmslite.entity.Camera;
import com.sun.jna.Native;
import java.awt.*;
import java.util.List;
import javax.swing.*;

/** Real native surfaces, without VLC, network or application startup. */
public final class TourSurfaceChecks {
    public static void main(String[] args) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JFrame frame = new JFrame();
            JPanel grid = new JPanel(null), controls = new JPanel();
            controls.setVisible(false);
            JPanel a = new JPanel(new BorderLayout()), b = new JPanel(new BorderLayout());
            Canvas av = new Canvas(), bv = new Canvas();
            a.add(av); b.add(bv);
            grid.add(a); grid.add(b); grid.add(controls);
            frame.add(grid);
            frame.setSize(900, 600);
            frame.addNotify();
            frame.validate();
            long ah = Native.getComponentID(av), bh = Native.getComponentID(bv);
            try {
                Camera other = new Camera("other", "rtsp://host/live", null, 1, 1);
                var positions = CameraGridLayout.positions(List.of(other), 3, true);
                for (int i = 0; i < 20; i++) {
                    JPanel active = i % 2 == 0 ? a : b, normal = i % 2 == 0 ? b : a;
                    CameraGridLayout.applyBounds(grid, List.of(controls, normal), positions, controls, active, 2, 3);
                    if (a.getParent() != grid || b.getParent() != grid
                            || Native.getComponentID(av) != ah || Native.getComponentID(bv) != bh) {
                        throw new AssertionError("Tour recreated/reparented a video surface");
                    }
                    if (active.getBounds().intersects(normal.getBounds())
                            || !active.getBounds().equals(controls.getBounds())
                            || active.getWidth() <= normal.getWidth()
                            || av.getWidth() != a.getWidth() || av.getHeight() != a.getHeight()
                            || bv.getWidth() != b.getWidth() || bv.getHeight() != b.getHeight()) {
                        throw new AssertionError("Invalid active/ordinary camera geometry");
                    }
                }
                System.out.println("OK: 20 tour switches preserve both native surfaces, with no overlap");
            } finally { frame.dispose(); }
        });
    }
}
