package br.com.jonmarques.vmslite.ui;

import com.formdev.flatlaf.FlatDarculaLaf;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;

public final class FullscreenControlsChecks {
    public static void main(String[] args) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            FlatDarculaLaf.setup();
            JFrame owner = new JFrame();
            FullscreenControls controls = new FullscreenControls(owner, () -> {});
            try {
                controls.setEnabled(true);
                JWindow window = (JWindow) owner.getOwnedWindows()[0];
                JButton button = (JButton) window.getContentPane();
                button.setSize(window.getSize());
                if (!button.isOpaque() || window.getBackground().getAlpha() != 255) {
                    throw new AssertionError("Fullscreen controls must be opaque");
                }
                int normal = paint(button);
                button.getModel().setRollover(true);
                int hover = paint(button);
                if (normal == hover) throw new AssertionError("Hover background must change");
                controls.setEnabled(false);
                if (window.isDisplayable()) throw new AssertionError("Window must be disposed on exit");
                System.out.println("OK: opaque fullscreen button, readable normal/hover states, disposal");
            } finally {
                controls.dispose();
                owner.dispose();
            }
        });
    }

    private static int paint(JButton button) {
        BufferedImage image = new BufferedImage(button.getWidth(), button.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try { button.paint(graphics); } finally { graphics.dispose(); }
        Color background = new Color(image.getRGB(10, 10), true);
        if (background.getAlpha() != 255 || background.getRed() < 100) {
            throw new AssertionError("Background is transparent or black");
        }
        int lightPixels = 0;
        for (int y = 5; y < image.getHeight() - 5; y++) {
            for (int x = 5; x < image.getWidth() - 5; x++) {
                Color pixel = new Color(image.getRGB(x, y), true);
                if (pixel.getRed() > 220 && pixel.getGreen() > 220 && pixel.getBlue() > 220) lightPixels++;
            }
        }
        if (lightPixels < 20) throw new AssertionError("Button label is not visible");
        return background.getRGB();
    }
}
