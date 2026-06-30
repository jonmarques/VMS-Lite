package br.com.jonmarques.vmslite;

import uk.co.caprica.vlcj.factory.MediaPlayerFactory;
import uk.co.caprica.vlcj.player.base.MediaPlayer;
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter;
import uk.co.caprica.vlcj.player.component.CallbackMediaPlayerComponent; // ALTERADO: Componente Leve (Lightweight)
import uk.co.caprica.vlcj.player.component.EmbeddedMediaPlayerComponent;

import javax.swing.*;
import br.com.jonmarques.vmslite.entity.Camera;
import br.com.jonmarques.vmslite.listener.GridDragListener;

import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

public class CameraPanel extends JPanel {

    private static final long serialVersionUID = 1L;

    private final Camera camera;
    
    // MUDANÇA CRÍTICA: Renderização via Software/Memória. Perfeito para grades e sobreposição de JLabels
    private final CallbackMediaPlayerComponent player;
    private final JLabel loadingLabel;
    private volatile boolean reconnecting = false;
    private boolean arrastando = false;
    private volatile boolean released = false;
    private MediaPlayerFactory factory;
    private volatile boolean bootInicializado = false;
    
    public CameraPanel(VMSLite vmslite, Camera camera) {
        this.camera = camera;
        setLayout(new BorderLayout());

        List<String> vlcArgs = new ArrayList<>();

     // 1. DESATIVAR HARDWARE (Mantido para estabilidade no JPackage)
     vlcArgs.add("--avcodec-hw=none"); 

     // 2. FORÇAR MULTITHREADING NA CPU
     vlcArgs.add("--ffmpeg-threads=2"); 

     // 3. PRIORIZAR TEMPO REAL
     vlcArgs.add("--drop-late-frames"); 
     vlcArgs.add("--skip-frames");      

     // 4. CACHE E SINCRONIA (Ajustados para evitar flickering)
     vlcArgs.add("--network-caching=400"); // Aumentado de 300 para 400ms para maior buffer
     vlcArgs.add("--live-caching=400");
     vlcArgs.add("--file-caching=400");
     vlcArgs.add("--clock-jitter=500000"); // Tolerância natural de 500ms (não force zero)
     vlcArgs.add("--clock-synchro=1");     // ATIVADO: Essencial para sincronizar e parar o "vai e volta"
     vlcArgs.add("--rtsp-tcp"); 

     // 5. SILENCIAR COMPONENTE
     vlcArgs.add("--no-audio");                
     vlcArgs.add("--no-video-title-show");     
     vlcArgs.add("--no-stats");                
     vlcArgs.add("--quiet");
     vlcArgs.add("--verbose=-1"); 

     // 6. OTIMIZAÇÃO DE CODEC
     vlcArgs.add("--avcodec-skiploopfilter=4"); 
     vlcArgs.add("--avcodec-fast");             
     vlcArgs.add("--rtsp-frame-buffer-size=2000000");
        this.factory = new MediaPlayerFactory(vlcArgs);
        // Instanciando o componente Lightweight (Callback)
        this.player = new CallbackMediaPlayerComponent();
        factory.mediaPlayers().newEmbeddedMediaPlayer();

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

        player.mediaPlayer().events().removeMediaEventListener(null); 

        player.mediaPlayer().events().addMediaPlayerEventListener(new MediaPlayerEventAdapter() {
            @Override
            public void playing(MediaPlayer mediaPlayer) {
                SwingUtilities.invokeLater(() -> {
                    loadingLabel.setVisible(false);
                    // CORREÇÃO: Removido o setScale(0) que quebrava câmeras específicas comprimindo-as para 0x0 pixels
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
        });

        if (!released) {
            player.mediaPlayer().media().play(camera.getUrl());
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
                    	// Em vez de dar play apenas com a URL, passe argumentos de escala da mídia
                    	// Em vez de usar os valores agressivos antigos, use estes:
                    	String[] options = {
                    	    ":rtsp-tcp",                // Essencial: Mantém a estabilidade
                    	    ":network-caching=400",     // Suba para 400ms para evitar falhas na rede
                    	    ":live-caching=400",
                    	    ":file-caching=400",
                    	    ":clock-synchro=1",         // OBRIGATÓRIO: Habilita sincronia para o vídeo não "pular"
                    	    ":clock-jitter=500000",     // Permite uma tolerância natural
                    	    ":avcodec-hw=none"          // Mude para 'none' para garantir que não haverá conflito de hardware
                    	};

                    	ok = player.mediaPlayer().media().play(camera.getUrl(), options);
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
            if (player.mediaPlayer() != null) {
                player.mediaPlayer().controls().stop();
            }
            player.release(); 
            if (factory != null) {
                factory.release(); 
            }
        } catch (Exception e) {
            System.err.println("Aviso: Erro ao liberar recursos nativos da câmera " + camera.getName());
        }
    }

    public Camera getConfig() {
        return camera;
    }
}