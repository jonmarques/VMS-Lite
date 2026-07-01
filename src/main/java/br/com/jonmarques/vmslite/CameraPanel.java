package br.com.jonmarques.vmslite;

import uk.co.caprica.vlcj.factory.MediaPlayerFactory;
import uk.co.caprica.vlcj.player.base.MediaPlayer;
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter;
import uk.co.caprica.vlcj.player.base.MediaPlayerEventListener;
import uk.co.caprica.vlcj.player.component.CallbackMediaPlayerComponent; // Componente Leve (Lightweight)

import javax.swing.*;
import br.com.jonmarques.vmslite.entity.Camera;
import br.com.jonmarques.vmslite.listener.GridDragListener;

import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

public class CameraPanel extends JPanel {

    private static final long serialVersionUID = 1L;

    private final Camera camera;

    // Renderização via Software/Memória. Perfeito para grades e sobreposição de JLabels
    private final CallbackMediaPlayerComponent player;
    private final JLabel loadingLabel;
    private volatile boolean reconnecting = false;
    private boolean arrastando = false;
    private volatile boolean released = false;
    private volatile boolean bootInicializado = false;

    private MediaPlayerEventListener currentListener;

    private static final String[] MEDIA_OPTIONS = {
        ":rtsp-tcp",
        ":network-caching=400",
        ":live-caching=400",
        ":file-caching=400",
        ":clock-synchro=1",
        ":clock-jitter=500000",
        ":avcodec-hw=any"
    };

    public CameraPanel(VMSLite vmslite, Camera camera, MediaPlayerFactory factory) {
        this.camera = camera;
        setLayout(new BorderLayout());

        this.player = new CallbackMediaPlayerComponent(
            factory, // MediaPlayerFactory compartilhada, com todas as otimizações
            null,    // FullScreenStrategy (não usado, fullscreen é tratado manualmente)
            null,    // InputEvents (mouse/teclado tratados manualmente com listeners próprios)
            true,    // lockBuffers
            null,    // RenderCallback (usa o painter padrão)
            null,    // BufferFormatCallback (usa o formato padrão)
            null     // JComponent videoSurfaceComponent (usa o componente leve padrão)
        );
        player.setOpaque(true);
        player.setBackground(Color.BLACK);
        // CORREÇÃO: removida a linha "factory.mediaPlayers().newEmbeddedMediaPlayer();"
        // Ela criava um segundo player nativo "fantasma", nunca usado e nunca liberado,
        // consumindo memória/threads à toa para cada câmera da grade.

        loadingLabel = new JLabel("Carregando...", SwingConstants.CENTER);
        loadingLabel.setOpaque(true);
        loadingLabel.setBackground(new Color(0, 0, 0, 180));
        loadingLabel.setForeground(Color.WHITE);

        JLayeredPane layer = new JLayeredPane() {
            @Override
            public void doLayout() {
                synchronized (getTreeLock()) {
                    int w = getWidth();
                    int h = getHeight();
                    for (Component c : getComponents()) {
                        c.setBounds(0, 0, w, h);
                    }
                }
            }
        };

        layer.add(player, JLayeredPane.DEFAULT_LAYER);
        layer.add(loadingLabel, JLayeredPane.PALETTE_LAYER);
        add(layer, BorderLayout.CENTER);

        // --- INTERCEPTAÇÃO DOS EVENTOS DE MOUSE ---
        MouseAdapter mouseAdapter = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) { verificarClique(e, vmslite); }
            @Override
            public void mouseReleased(MouseEvent e) { verificarClique(e, vmslite); }
        };

        GridDragListener dragListener = new GridDragListener(vmslite, this);

        Component videoSurface = player.videoSurfaceComponent();

        if (videoSurface != null) {
            videoSurface.addMouseListener(mouseAdapter);
            videoSurface.addMouseListener(dragListener);
            videoSurface.addMouseMotionListener(dragListener);

            videoSurface.addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
                @Override
                public void mouseMoved(java.awt.event.MouseEvent e) {
                    if (vmslite.isFullscreen()) {
                        Point pontoNaTela = e.getLocationOnScreen();
                        vmslite.gerenciarBotaoJanela(pontoNaTela);
                    }
                }
            });
        }

        // Listeners de redundância para as camadas superiores do Swing
        loadingLabel.addMouseListener(mouseAdapter);
        loadingLabel.addMouseListener(dragListener);
        loadingLabel.addMouseMotionListener(dragListener);

        layer.addMouseListener(mouseAdapter);
        layer.addMouseListener(dragListener);
        layer.addMouseMotionListener(dragListener);

        this.addMouseListener(mouseAdapter);
        this.addMouseListener(dragListener);
        this.addMouseMotionListener(dragListener);
    }

    private void verificarClique(MouseEvent e, VMSLite vmslite) {
        if (e.isPopupTrigger() || SwingUtilities.isRightMouseButton(e)) {
            vmslite.showEditCameraDialog(CameraPanel.this);
        }
    }

    public void setArrastando(boolean arrastando) {
        this.arrastando = arrastando;
        if (arrastando) {
            player.setVisible(false);
            loadingLabel.setText("Movendo: " + camera.getName());
            loadingLabel.setBackground(new Color(30, 144, 255, 200));
            loadingLabel.setVisible(true);
        } else {
            player.setVisible(true);
            loadingLabel.setBackground(new Color(0, 0, 0, 180));
            loadingLabel.setVisible(false);
        }
        revalidate();
        repaint();
    }

    public void start() {
        loadingLabel.setVisible(true);
        loadingLabel.setText("Carregando...");

        // CORREÇÃO: remove de fato o listener anterior (se existir) antes de adicionar um novo.
        // Antes: removeMediaEventListener(null) não tinha efeito nenhum, então cada chamada de
        // start() empilhava um novo listener, multiplicando callbacks (playing/error/stopped)
        // e disparando múltiplas tentativas de reconexão simultâneas.
        if (currentListener != null) {
            player.mediaPlayer().events().removeMediaPlayerEventListener(currentListener);
        }

        currentListener = new MediaPlayerEventAdapter() {
            @Override
            public void playing(MediaPlayer mediaPlayer) {
                SwingUtilities.invokeLater(() -> {
                    loadingLabel.setVisible(false);
                    // Removido o setScale(0) que quebrava câmeras específicas comprimindo-as para 0x0 pixels
                    player.mediaPlayer().video().setAspectRatio(null);
                });
                bootInicializado = true;
            }

            @Override
            public void error(MediaPlayer mediaPlayer) {
                if (released) return;
                SwingUtilities.invokeLater(() -> {
                    loadingLabel.setVisible(true);
                    loadingLabel.setText("Reconectando...");
                });
                reconnect();
            }

            @Override
            public void stopped(MediaPlayer mediaPlayer) {
                if (!released && isDisplayable() && bootInicializado) {
                    reconnect();
                } else {
                    System.out.println("Aviso: 'Stopped' ignorado durante a montagem inicial da grade para: " + camera.getName());
                }
            }
        };
        player.mediaPlayer().events().addMediaPlayerEventListener(currentListener);

        if (!released) {
            player.mediaPlayer().media().play(camera.getUrl(), MEDIA_OPTIONS);
        }
    }

    private void reconnect() {
        if (reconnecting || released) return;
        reconnecting = true;

        new Thread(() -> {
            while (reconnecting && !released) {
                try {
                    Thread.sleep(5000);
                    if (released) break;

                    SwingUtilities.invokeLater(() -> {
                        if (!released) {
                            loadingLabel.setVisible(true);
                            loadingLabel.setText("Reconectando...");
                        }
                    });

                    if (!isDisplayable() || released) continue;

                    boolean ok = false;
                    if (!released && player.mediaPlayer() != null) {
                        ok = player.mediaPlayer().media().play(camera.getUrl(), MEDIA_OPTIONS);
                    }

                    if (ok || released) {
                        reconnecting = false;
                        break;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    reconnecting = false;
                    break;
                } catch (Exception ignored) {}
            }
        }, "Reconnect-" + camera.getName()).start();
    }

    public void stop() {
        this.released = true;
        this.reconnecting = false;

        try {
            if (currentListener != null && player.mediaPlayer() != null) {
                player.mediaPlayer().events().removeMediaPlayerEventListener(currentListener);
                currentListener = null;
            }
            if (player.mediaPlayer() != null) {
                player.mediaPlayer().controls().stop();
            }
            player.release();

            // CORREÇÃO: removido "factory.release()" daqui.
            // Se a MediaPlayerFactory for compartilhada entre as câmeras da grade (recebida
            // por parâmetro no construtor, sugerindo isso), liberá-la ao fechar UMA câmera
            // derruba o player nativo de TODAS as outras, causando travamentos/reconexões
            // em cascata. O release da factory deve acontecer uma única vez, no encerramento
            // da aplicação (em VMSLite), não aqui.
        } catch (Exception e) {
            System.err.println("Aviso: Erro ao liberar recursos nativos da câmera " + camera.getName());
        }
    }

    public Camera getConfig() {
        return camera;
    }
}