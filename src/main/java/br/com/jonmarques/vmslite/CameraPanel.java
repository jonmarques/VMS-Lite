package br.com.jonmarques.vmslite;

import br.com.jonmarques.vmslite.entity.Camera;
import br.com.jonmarques.vmslite.listener.GridDragListener;
import br.com.jonmarques.vmslite.playback.CameraPlayback;
import br.com.jonmarques.vmslite.playback.VlcVideoPlayer;
import br.com.jonmarques.vmslite.service.OnvifDiscoveryService;
import uk.co.caprica.vlcj.player.component.EmbeddedMediaPlayerComponent;
import java.awt.*;
import java.awt.event.*;
import java.util.concurrent.Future;
import javax.swing.*;

public class CameraPanel extends JPanel {
    private static final long serialVersionUID = 1L;
    private final Camera camera;
    private final EmbeddedMediaPlayerComponent player;
    private final JLabel loadingLabel;
    private final VMSLite vmslite;
    private final CameraPlayback playback;
    private CameraPlayback.State state = CameraPlayback.State.LOADING;
    private boolean dragging;

    public CameraPanel(final VMSLite vmslite, Camera camera) {
        this.vmslite = vmslite;
        this.camera = camera;
        this.setLayout(new BorderLayout());
        setOpaque(true);
        setBackground(Color.BLACK);

        this.player = VlcManager.createPlayer();

        this.playback = new CameraPlayback(camera, new VlcVideoPlayer(player), this::onPlaybackState, this::refreshAddress);
        this.loadingLabel = new JLabel("Carregando...", 0);
        this.loadingLabel.setOpaque(true);
        this.loadingLabel.setBackground(Color.BLACK);
        this.loadingLabel.setForeground(Color.WHITE);

        JLayeredPane layer = new JLayeredPane() {
            private static final long serialVersionUID = 1L;

            @Override
            public void doLayout() {
                synchronized (this.getTreeLock()) {
                    int w = this.getWidth();
                    int h = this.getHeight();
                    for (Component c : this.getComponents()) {
                        c.setBounds(0, 0, w, h);
                    }
                }
            }
        };

        layer.setOpaque(true);
        layer.setBackground(Color.BLACK);
        player.setBackground(Color.BLACK);
        layer.add(this.player, JLayeredPane.DEFAULT_LAYER);
        layer.add(this.loadingLabel, JLayeredPane.PALETTE_LAYER);
        this.add(layer, BorderLayout.CENTER);

        MouseAdapter mouseAdapter = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                CameraPanel.this.verificarClique(e, vmslite);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                CameraPanel.this.verificarClique(e, vmslite);
            }
        };

        GridDragListener dragListener = new GridDragListener(vmslite, this);

        // IMPORTANTE: com EmbeddedMediaPlayerComponent, videoSurfaceComponent() é
        // um Canvas heavyweight, que captura os eventos de mouse antes deles
        // chegarem ao JLayeredPane. Por isso é necessário registrar os listeners
        // DIRETAMENTE nele também, não só no layer.
        Component videoSurface = this.player.videoSurfaceComponent();
        if (videoSurface != null) {
            videoSurface.addMouseListener(mouseAdapter);
            videoSurface.addMouseListener(dragListener);
            videoSurface.addMouseMotionListener(dragListener);
            videoSurface.addMouseMotionListener(new MouseMotionAdapter() {
                @Override
                public void mouseMoved(MouseEvent e) {
                    if (vmslite.isFullscreen()) {
                        Point pontoNaTela = e.getLocationOnScreen();
                        vmslite.gerenciarBotaoJanela(pontoNaTela);
                    }
                }
            });
        }

        this.loadingLabel.addMouseListener(mouseAdapter);
        this.loadingLabel.addMouseListener(dragListener);
        this.loadingLabel.addMouseMotionListener(dragListener);

        layer.addMouseListener(mouseAdapter);
        layer.addMouseListener(dragListener);
        layer.addMouseMotionListener(dragListener);

        this.addMouseListener(mouseAdapter);
        this.addMouseListener(dragListener);
        this.addMouseMotionListener(dragListener);
    }

    private void verificarClique(MouseEvent e, VMSLite vmslite) {
        if (e.isPopupTrigger()) {
            vmslite.showEditCameraDialog(this);
        }
    }

    public void setArrastando(boolean arrastando) {
        dragging = arrastando;
        updateOverlay();
    }

    private void onPlaybackState(CameraPlayback.State newState) {
        SwingUtilities.invokeLater(() -> {
            if (playback.isClosed()) return;
            state = newState;
            updateOverlay();
        });
    }

    private void updateOverlay() {
        player.setVisible(!dragging);
        loadingLabel.setBackground(dragging ? new Color(30, 144, 255) : Color.BLACK);
        loadingLabel.setText(dragging ? "Movendo: " + camera.getName()
                : state == CameraPlayback.State.RECONNECTING ? "Reconectando..." : "Carregando...");
        loadingLabel.setVisible(dragging || state != CameraPlayback.State.PLAYING);
        repaint();
    }

    private void refreshAddress() {
        OnvifDiscoveryService.discoverDevicesForReconnect(devices -> SwingUtilities.invokeLater(() -> {
            if (playback.isClosed()) return;
            String ip = OnvifDiscoveryService.encontrarIpPorUuid(devices, camera.getUuid());
            String oldIp = OnvifDiscoveryService.extrairIpDaUrl(camera.getUrl());
            if (ip != null && oldIp != null && !ip.equals(oldIp)) {
                camera.setUrl(OnvifDiscoveryService.substituirIpNaUrl(camera.getUrl(), ip));
                vmslite.saveConfigs();
            }
        }));
    }

    public void start() { playback.start(); }
    public CameraPlayback.State getPlaybackState() { return state; }
    public void sample(java.util.function.Consumer<br.com.jonmarques.vmslite.playback.PlaybackSample> callback) {
        playback.sample(callback);
    }
    public Future<?> stop() { return playback.stop(); }
    public Camera getConfig() { return camera; }
    public static boolean shutdownExecutors() { return CameraPlayback.shutdownExecutors(); }
}
