package br.com.jonmarques.vmslite;

import br.com.jonmarques.vmslite.entity.Camera;
import br.com.jonmarques.vmslite.listener.GridDragListener;
import br.com.jonmarques.vmslite.service.OnvifDiscoveryService;
import uk.co.caprica.vlcj.player.base.MediaPlayer;
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter;
import uk.co.caprica.vlcj.player.base.MediaPlayerEventListener;
import uk.co.caprica.vlcj.player.component.CallbackMediaPlayerComponent;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Point;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import javax.swing.JLabel;
import javax.swing.JLayeredPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

public class CameraPanel extends JPanel {
   private static final long serialVersionUID = 1L;
   private static final boolean DEBUG = Boolean.getBoolean("vmslite.debug");
   private static final ScheduledExecutorService RECONNECT_EXECUTOR = Executors.newScheduledThreadPool(
         Math.max(2, Runtime.getRuntime().availableProcessors() / 2),
         new ThreadFactory() {
            private int count = 1;

            @Override
            public Thread newThread(Runnable runnable) {
               Thread thread = new Thread(runnable, "VMSLite-Reconnect-" + count++);
               thread.setDaemon(true);
               return thread;
            }
         });

   private static final String[] MEDIA_OPTIONS = new String[]{
         ":drop-late-frames",
         ":skip-frames",
         ":framedrop",

         ":avcodec-fast",
         ":avcodec-skiploopfilter=4",
         ":rtsp-tcp",

         ":no-video-title-show",

         ":network-caching=1000",
         ":live-caching=1000",

         ":clock-synchro=0",
         ":clock-jitter=0",

         ":avcodec-hw=any"
   };

   private final Camera camera;
   private final CallbackMediaPlayerComponent player;
   private final JLabel loadingLabel;
   private final Object lifecycleLock = new Object();
   private final VMSLite vmslite;

   private volatile boolean reconnecting = false;
   private volatile boolean released = false;
   private volatile boolean bootInicializado = false;

   private MediaPlayerEventListener currentListener;
   private ScheduledFuture<?> reconnectTask;
   private int reconnectAttempts = 0;

   public CameraPanel(final VMSLite vmslite, Camera camera) {
      this.vmslite = vmslite;
      this.camera = camera;
      this.setLayout(new BorderLayout());

      this.player = VlcManager.createPlayer();
      this.player.mediaPlayer().audio().setMute(true);
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
      if (e.isPopupTrigger() || SwingUtilities.isRightMouseButton(e)) {
         vmslite.showEditCameraDialog(this);
      }
   }

   public void setArrastando(boolean arrastando) {
      if (arrastando) {
         this.player.setVisible(false);
         this.loadingLabel.setText("Movendo: " + this.camera.getName());
         this.loadingLabel.setBackground(new Color(30, 144, 255, 200));
         this.loadingLabel.setVisible(true);
      } else {
         this.player.setVisible(true);
         this.loadingLabel.setBackground(new Color(0, 0, 0, 180));
         this.loadingLabel.setVisible(false);
      }
      this.revalidate();
      this.repaint();
   }

   public void start() {
      SwingUtilities.invokeLater(() -> {
         this.loadingLabel.setVisible(true);
         this.loadingLabel.setText("Carregando...");
      });

      if (this.currentListener != null) {
         this.player.mediaPlayer().events().removeMediaPlayerEventListener(this.currentListener);
      }

      this.currentListener = new MediaPlayerEventAdapter() {
         @Override
         public void playing(MediaPlayer mediaPlayer) {
            CameraPanel.this.reconnecting = false;
            CameraPanel.this.cancelReconnectTask();
            SwingUtilities.invokeLater(() -> {
               synchronized (CameraPanel.this.lifecycleLock) {
                  if (CameraPanel.this.released) return;
                  CameraPanel.this.loadingLabel.setVisible(false);
                  CameraPanel.this.player.mediaPlayer().video().setAspectRatio((String) null);
               }
            });
            CameraPanel.this.bootInicializado = true;
         }

         @Override
         public void error(MediaPlayer mediaPlayer) {
            if (!CameraPanel.this.released) {
               SwingUtilities.invokeLater(() -> {
                  CameraPanel.this.loadingLabel.setVisible(true);
                  CameraPanel.this.loadingLabel.setText("Reconectando...");
               });
               CameraPanel.this.reconnect();
            }
         }

         @Override
         public void stopped(MediaPlayer mediaPlayer) {
            if (!CameraPanel.this.released && CameraPanel.this.isDisplayable() && CameraPanel.this.bootInicializado) {
               CameraPanel.this.reconnect();
            } else {
               logDebug("Stopped ignorado durante montagem inicial da grade para: " + CameraPanel.this.camera.getName());
            }
         }
      };

      this.player.mediaPlayer().events().addMediaPlayerEventListener(this.currentListener);

      synchronized (lifecycleLock) {
         if (!this.released) {
            this.player.mediaPlayer().media().play(this.camera.getUrl(), MEDIA_OPTIONS);
         }
      }
   }

   private void reconnect() {
      if (this.reconnecting || this.released) {
         return;
      }

      this.reconnecting = true;
      this.reconnectAttempts = 0;
      this.reconnectTask = RECONNECT_EXECUTOR.scheduleWithFixedDelay(
            this::tryReconnect,
            5,
            5,
            TimeUnit.SECONDS);
   }

   private void tryReconnect() {
      if (!this.reconnecting || this.released) {
         cancelReconnectTask();
         return;
      }

      SwingUtilities.invokeLater(() -> {
         if (!this.released) {
            this.loadingLabel.setVisible(true);
            this.loadingLabel.setText("Reconectando...");
         }
      });

      if (!this.isDisplayable()) {
         return;
      }

      synchronized (lifecycleLock) {
         if (this.released || !this.reconnecting || this.player.mediaPlayer() == null) {
            return;
         }

         this.reconnectAttempts++;

         if (this.reconnectAttempts % 3 == 0) {
            logDebug("Forcando hard reset de midia nativa para: " + camera.getName());
            this.player.mediaPlayer().media().prepare(this.camera.getUrl(), MEDIA_OPTIONS);
         } else if (this.reconnectAttempts >= 5 && this.reconnectAttempts % 10 == 0) {
            atualizarIpPorUuid();
         }

         this.player.mediaPlayer().media().play(this.camera.getUrl(), MEDIA_OPTIONS);
      }
   }

   private void atualizarIpPorUuid() {
      OnvifDiscoveryService.discoverDevicesForReconnect(dispositivos -> {
         String uuidSalvo = this.camera.getUuid();
         String ipAntigo = OnvifDiscoveryService.extrairIpDaUrl(this.camera.getUrl());
         String ipEncontrado = OnvifDiscoveryService.encontrarIpPorUuid(dispositivos, uuidSalvo);

         if (ipEncontrado != null && ipAntigo != null && !ipAntigo.equals(ipEncontrado)) {
            logDebug("Novo IP '" + ipEncontrado + "' encontrado para: " + this.camera.getName());

            String novoUrl = OnvifDiscoveryService.substituirIpNaUrl(this.camera.getUrl(), ipEncontrado);
            this.camera.setUrl(novoUrl);

            synchronized (lifecycleLock) {
               if (!this.released && this.player.mediaPlayer() != null) {
                  this.player.mediaPlayer().media().prepare(novoUrl, MEDIA_OPTIONS);
               }
            }
            vmslite.saveConfigs();
         } else if (ipEncontrado == null) {
            logDebug("Nao foi possivel localizar a camera '" + this.camera.getName() + "' na rede via UUID.");
         }
      });
   }

   private void cancelReconnectTask() {
      ScheduledFuture<?> task = this.reconnectTask;
      if (task != null) {
         task.cancel(false);
         this.reconnectTask = null;
      }
   }

   public void stop() {
      this.released = true;
      this.reconnecting = false;
      cancelReconnectTask();

      synchronized (lifecycleLock) {
         try {
            if (this.currentListener != null && this.player.mediaPlayer() != null) {
               this.player.mediaPlayer().events().removeMediaPlayerEventListener(this.currentListener);
               this.currentListener = null;
            }

            if (this.player.mediaPlayer() != null) {
               this.player.mediaPlayer().controls().stop();
            }

            this.player.release();
         } catch (Exception var2) {
            System.err.println("Aviso: Erro ao liberar recursos nativos da camera " + this.camera.getName());
         }
      }
   }

   public Camera getConfig() {
      return this.camera;
   }

   private static void logDebug(String message) {
      if (DEBUG) {
         System.out.println(message);
      }
   }
}
