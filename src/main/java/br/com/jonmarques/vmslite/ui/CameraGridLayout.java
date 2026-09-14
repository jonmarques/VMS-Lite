package br.com.jonmarques.vmslite.ui;

import br.com.jonmarques.vmslite.CameraPanel;
import br.com.jonmarques.vmslite.entity.Camera;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JPanel;

/** Sparse placement avoids allocating an occupancy matrix on every resize. */
public final class CameraGridLayout {
    private CameraGridLayout() {}

    public static List<Rectangle> positions(List<Camera> cameras, int columns) {
        return positions(cameras, columns, false);
    }

    public static List<Rectangle> positions(List<Camera> cameras, int columns, boolean reserveTour) {
        int cols = Math.max(reserveTour ? 2 : 1, columns);
        List<Rectangle> positions = new ArrayList<>();
        if (reserveTour) positions.add(new Rectangle(0, 0, 2, 2));
        for (Camera camera : cameras) {
            int width = Math.min(cols, Math.max(1, camera.getColSpan()));
            int height = Math.max(1, camera.getRowSpan());
            int row = 0;
            boolean found = false;
            while (!found) {
                int nextRow = Integer.MAX_VALUE;
                for (int col = 0; col <= cols - width; col++) {
                    Rectangle candidate = new Rectangle(col, row, width, height);
                    Rectangle collision = null;
                    for (Rectangle occupied : positions) {
                        if (occupied.intersects(candidate)) {
                            collision = occupied;
                            break;
                        }
                    }
                    if (collision == null) {
                        positions.add(candidate);
                        found = true;
                        break;
                    }
                    nextRow = Math.min(nextRow, collision.y + collision.height);
                }
                if (!found) row = nextRow;
            }
        }
        return positions;
    }

    public static void apply(JPanel container, List<CameraPanel> panels, int rows, int cols) {
        apply(container, panels, null, rows, cols);
    }

    public static void apply(JPanel container, List<CameraPanel> panels, JPanel tour, int rows, int cols) {
        apply(container, panels, tour, null, rows, cols);
    }

    public static void apply(JPanel container, List<CameraPanel> panels, JPanel tour,
                             CameraPanel active, int rows, int cols) {
        if (container.getWidth() <= 0 || container.getHeight() <= 0) return;
        cols = Math.max(tour == null ? 1 : 2, cols);
        rows = Math.max(tour == null ? 1 : 2, rows);
        List<CameraPanel> ordinary = panels.stream().filter(panel -> tour == null || panel != active).toList();
        List<Rectangle> positions = positions(ordinary.stream().map(CameraPanel::getConfig).toList(), cols, tour != null);
        List<java.awt.Component> components = new ArrayList<>();
        if (tour != null) components.add(tour);
        components.addAll(ordinary);
        applyBounds(container, components, positions, tour, active, rows, cols);
    }

    static void applyBounds(JPanel container, List<java.awt.Component> components, List<Rectangle> positions,
                            JPanel tour, JPanel active, int rows, int cols) {
        for (Rectangle position : positions) rows = Math.max(rows, position.y + position.height);
        int gapX = Math.min(4, container.getWidth() / cols);
        int gapY = Math.min(4, container.getHeight() / rows);
        int availableWidth = container.getWidth() - (cols - 1) * gapX;
        int availableHeight = container.getHeight() - (rows - 1) * gapY;
        for (int i = 0; i < components.size(); i++) {
            Rectangle p = positions.get(i);
            int x = (int) ((long) p.x * availableWidth / cols) + p.x * gapX;
            int y = (int) ((long) p.y * availableHeight / rows) + p.y * gapY;
            int right = (int) ((long) (p.x + p.width) * availableWidth / cols) + (p.x + p.width - 1) * gapX;
            int bottom = (int) ((long) (p.y + p.height) * availableHeight / rows) + (p.y + p.height - 1) * gapY;
            components.get(i).setBounds(x, y, Math.max(0, right - x), Math.max(0, bottom - y));
            if (i == 0 && tour != null && active != null) {
                // Keep the native Canvas attached: only change geometry.
                active.setBounds(x, y, Math.max(0, right - x), Math.max(0, bottom - y));
            }
        }
        container.invalidate();
        container.validate();
        container.repaint();
    }
}
