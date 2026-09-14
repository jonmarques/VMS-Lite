package br.com.jonmarques.vmslite.ui;

import com.sun.jna.Native;
import java.awt.BorderLayout;
import java.awt.Canvas;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import uk.co.caprica.vlcj.player.embedded.fullscreen.windows.Win32FullScreenStrategy;

/** Uses a hidden native window; no cameras, network or application startup. */
public final class FullscreenSurfaceChecks {
    public static void main(String[] args) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JFrame frame = new JFrame();
            Canvas surface = new Canvas();
            JPanel toolbar = new JPanel();
            frame.add(toolbar, BorderLayout.NORTH);
            frame.add(surface, BorderLayout.CENTER);
            frame.setBounds(80, 80, 640, 480);
            frame.addNotify();
            try {
                long windowHandle = Native.getComponentID(frame);
                long surfaceHandle = Native.getComponentID(surface);
                Win32FullScreenStrategy strategy = new Win32FullScreenStrategy(frame);
                for (int i = 0; i < 5; i++) {
                    strategy.enterFullScreenMode();
                    toolbar.setVisible(false);
                    frame.validate();
                    checkHandles(frame, surface, windowHandle, surfaceHandle);
                    strategy.exitFullScreenMode();
                    toolbar.setVisible(true);
                    frame.validate();
                    checkHandles(frame, surface, windowHandle, surfaceHandle);
                }
                if (frame.isVisible()) throw new AssertionError("Test window should remain hidden");
                System.out.println("OK: five fullscreen round trips preserved window and video Canvas handles");
            } finally {
                frame.dispose();
            }
        });
    }

    private static void checkHandles(JFrame frame, Canvas surface, long windowHandle, long surfaceHandle) {
        if (!frame.isDisplayable() || !surface.isDisplayable()
                || Native.getComponentID(frame) != windowHandle
                || Native.getComponentID(surface) != surfaceHandle) {
            throw new AssertionError("Fullscreen recreated a native surface");
        }
    }
}
