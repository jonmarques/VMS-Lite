package br.com.jonmarques.vmslite;

import br.com.jonmarques.vmslite.entity.Camera;
import br.com.jonmarques.vmslite.listener.GridDragListener;
import br.com.jonmarques.vmslite.service.OnvifDiscoveryService;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Point;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;

import javax.swing.JLabel;
import javax.swing.JLayeredPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import uk.co.caprica.vlcj.player.base.MediaPlayer;
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter;
import uk.co.caprica.vlcj.player.base.MediaPlayerEventListener;
import uk.co.caprica.vlcj.player.component.CallbackMediaPlayerComponent;

public class CameraPanel extends JPanel {
   private static final long serialVersionUID = 1L;
   private final Camera camera;
   private final CallbackMediaPlayerComponent player;
   private final JLabel loadingLabel;
   
   private volatile boolean reconnecting = false;
   private volatile boolean released = false;
   private volatile boolean bootInicializado = false;
   
   private final Object lifecycleLock = new Object();
   private MediaPlayerEventListener currentListener;
   private VMSLite vmslite;

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
   
   public CameraPanel(final VMSLite vmslite, Camera camera) {
	   this.vmslite = vmslite;
      this.camera = camera;
      this.setLayout(new BorderLayout());
      
      this.player = new CallbackMediaPlayerComponent();
      this.player.mediaPlayer().audio().setMute(true);
      this.loadingLabel = new JLabel("Carregando...", 0);
      this.loadingLabel.setOpaque(true);
      this.loadingLabel.setBackground(Color.BLACK);
      this.loadingLabel.setForeground(Color.WHITE);
      
      JLayeredPane layer = new JLayeredPane() {
         private static final long serialVersionUID = 1L;
         public void doLayout() {
            synchronized(this.getTreeLock()) {
               int w = this.getWidth();
               int h = this.getHeight();
               Component[] components = this.getComponents();
               for (Component c : components) {
                  c.setBounds(0, 0, w, h);
               }
            }
         }
      };
      
      layer.add(this.player, JLayeredPane.DEFAULT_LAYER);
      layer.add(this.loadingLabel, JLayeredPane.PALETTE_LAYER);
      this.add(layer, "Center");
      
      MouseAdapter mouseAdapter = new MouseAdapter() {
         public void mousePressed(MouseEvent e) {
            CameraPanel.this.verificarClique(e, vmslite);
         }
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
      this.loadingLabel.setVisible(true);
      this.loadingLabel.setText("Carregando...");
      
      if (this.currentListener != null) {
         this.player.mediaPlayer().events().removeMediaPlayerEventListener(this.currentListener);
      }

      this.currentListener = new MediaPlayerEventAdapter() {
         @Override
         public void playing(MediaPlayer mediaPlayer) {
            CameraPanel.this.reconnecting = false; 
            SwingUtilities.invokeLater(() -> {
               synchronized (CameraPanel.this.lifecycleLock) {
                  if (CameraPanel.this.released) return;
                  CameraPanel.this.loadingLabel.setVisible(false);
                  CameraPanel.this.player.mediaPlayer().video().setAspectRatio((String)null);
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
               System.out.println("Aviso: 'Stopped' ignorado durante a montagem inicial da grade para: " + CameraPanel.this.camera.getName());
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

	   new Thread(() -> {
	      int tentativas = 0;
	      while (this.reconnecting && !this.released) {
	         try {
	            if (!esperarComPolling(5000)) break;
	            if (this.released) break;

	            SwingUtilities.invokeLater(() -> {
	               if (!this.released) {
	                  this.loadingLabel.setVisible(true);
	                  this.loadingLabel.setText("Reconectando...");
	               }
	            });

	            if (!this.isDisplayable() || this.released) {
	               continue;
	            }

	            synchronized (lifecycleLock) {
	               if (!this.released && this.reconnecting && this.player.mediaPlayer() != null) {
	                  tentativas++;

	                  if (tentativas % 3 == 0) {
	                     System.out.println("[VMSLite] Forçando Hard Reset de Mídia Nativa para: " + camera.getName());
	                     this.player.mediaPlayer().media().prepare(this.camera.getUrl(), MEDIA_OPTIONS);
	                  } else if (tentativas >= 5 && tentativas % 10 == 0) {

	                	    OnvifDiscoveryService.discoverDevices(dispositivos -> {

	                	        String uuidSalvo = this.camera.getUuid();
	                	        String ipAntigo = OnvifDiscoveryService.extrairIpDaUrl(this.camera.getUrl());
	                	        String ipEncontrado = OnvifDiscoveryService.encontrarIpPorUuid(dispositivos, uuidSalvo);

	                	        if (ipEncontrado != null && ipAntigo != null && !ipAntigo.equals(ipEncontrado)) {
	                	            System.out.println("Novo IP '" + ipEncontrado + "' encontrado para: " + this.camera.getName());

	                	            String novoUrl = OnvifDiscoveryService.substituirIpNaUrl(this.camera.getUrl(), ipEncontrado);
	                	            this.camera.setUrl(novoUrl);

	                	            synchronized (lifecycleLock) {
	                	                if (!this.released && this.player.mediaPlayer() != null) {
	                	                    this.player.mediaPlayer().media().prepare(novoUrl, MEDIA_OPTIONS);
	                	                }
	                	            }
	                	            vmslite.saveConfigs();
	                	        } else if (ipEncontrado == null) {
	                	            System.out.println("Não foi possível localizar a câmera '" + this.camera.getName() + "' na rede (via UUID).");
	                	        }
	                	    });
	                	}
	                  

	                  this.player.mediaPlayer().media().play(this.camera.getUrl(), MEDIA_OPTIONS);
	               }
	            }

	         } catch (Exception e) {
	            // Protege a execução da thread em background contra quebras de ponteiro
	         }
	      }
	   }, "Reconnect-" + this.camera.getName()).start();
	}

   private boolean esperarComPolling(long totalMs) {
      long restante = totalMs;
      while (restante > 0) {
         if (!reconnecting || released) return false;
         long passo = Math.min(200, restante);
         try {
            Thread.sleep(passo);
         } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
         }
         restante -= passo;
      }
      return reconnecting && !released;
   }

   public void stop() {
      this.released = true;
      this.reconnecting = false;

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
            System.err.println("Aviso: Erro ao liberar recursos nativos da câmera " + this.camera.getName());
         }
      }
   }

   public Camera getConfig() {
      return this.camera;
   }
}