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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
			4,
			new ThreadFactory() {
				private int count = 1;

				@Override
				public Thread newThread(Runnable runnable) {
					Thread thread = new Thread(runnable, "VMSLite-Reconnect-" + count++);
					thread.setDaemon(true);
					return thread;
				}
			});

	private static final ExecutorService RELEASE_EXECUTOR =
		    Executors.newFixedThreadPool(3, r -> {
		        Thread t = new Thread(r, "camera-release");
		        t.setDaemon(true);
		        return t;
		    });
	
	private static final String[] MEDIA_OPTIONS = new String[]{
	         ":network-caching=1000",           
	         ":live-caching=1000",

	         ":clock-synchro=1",               
	         ":clock-jitter=0",                

	         ":framedrop",  
	         ":drop-late-frames",

	         ":avcodec-hw=none",
	         ":avcodec-fast",
	         ":avcodec-threads=1",          
	         ":avcodec-skiploopfilter=4",
	         
	         ":audio-track-id=-1",             
	         ":no-audio-time-sync",            

	         ":rtsp-tcp", 
	         ":no-video-title-show",
	         ":rtsp-timeout=8",
	         ":network-timeout=8000"
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
			if (!loadingLabel.isVisible()) {
			    loadingLabel.setVisible(true);
			}
		} else {
			this.player.setVisible(true);
			this.loadingLabel.setBackground(new Color(0, 0, 0, 180));
			if (loadingLabel.isVisible()) {
			    loadingLabel.setVisible(false);
			}		
		}
		this.repaint();
	}

	public void start() {
		SwingUtilities.invokeLater(() -> {
			if (!loadingLabel.isVisible()) {
			    loadingLabel.setVisible(true);
			}
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

				// Correção: Sempre atualizar componentes Swing (loadingLabel) dentro da EDT
				SwingUtilities.invokeLater(() -> {
					synchronized (CameraPanel.this.lifecycleLock) {
						if (CameraPanel.this.released) return;
						if (loadingLabel.isVisible()) {
						    loadingLabel.setVisible(false);
						}						CameraPanel.this.player.mediaPlayer().video().setAspectRatio(null);
					}
				});
				CameraPanel.this.bootInicializado = true;
			}

			@Override
			public void error(MediaPlayer mediaPlayer) {
				if (!CameraPanel.this.released) {
					logDebug("Erro de reprodução detectado para: " + CameraPanel.this.camera.getName());

					SwingUtilities.invokeLater(() -> {
						if (!loadingLabel.isVisible()) {
						    loadingLabel.setVisible(true);
						}
						CameraPanel.this.loadingLabel.setText("Reconectando...");
					});

					// REGRA DO VLCJ: Usar o submit para não chamar o VLC de dentro do evento do VLC
					mediaPlayer.submit(() -> {
						CameraPanel.this.reconnect();
					});
				}
			}

			@Override
			public void stopped(MediaPlayer mediaPlayer) {
				if (!CameraPanel.this.released && CameraPanel.this.isDisplayable() && CameraPanel.this.bootInicializado) {
					logDebug("Stream parado para: " + CameraPanel.this.camera.getName());

					// REGRA DO VLCJ: Usar o submit para evitar Deadlock nativo
					mediaPlayer.submit(() -> {
						CameraPanel.this.reconnect();
					});
				} else {
					logDebug("Stopped ignorado durante montagem inicial para: " + CameraPanel.this.camera.getName());
				}
			}

			@Override
			public void videoOutput(MediaPlayer mediaPlayer, int newCount) {
				// Se o contador de saída de vídeo for para 0, significa que o sinal caiu/congelou
				if (newCount == 0 && CameraPanel.this.bootInicializado && !CameraPanel.this.released) {
					logDebug("Sinal de vídeo sumiu (newCount=0) para: " + CameraPanel.this.camera.getName());

					mediaPlayer.submit(() -> {
						CameraPanel.this.reconnect();
					});
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
				if (!loadingLabel.isVisible()) {
				    loadingLabel.setVisible(true);
				}
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


	public Future<?> stop() {

	    released = true;
	    reconnecting = false;

	    cancelReconnectTask();


	    try {
	        if(currentListener != null){

	            player.mediaPlayer()
	                  .events()
	                  .removeMediaPlayerEventListener(currentListener);

	            currentListener = null;
	        }

	    } catch(Exception ignored){}


	    if(RELEASE_EXECUTOR.isShutdown()){
	        return null;
	    }


	    return RELEASE_EXECUTOR.submit(() -> {

	        try {

	            MediaPlayer mp = player.mediaPlayer();


	            if(mp != null){

	                try {
	                    mp.controls().stop();
	                } catch(Exception ignored){}


	                Thread.sleep(200);
	            }


	            player.release();


	        }catch(Exception e){

	            System.err.println(
	                "Erro liberando "+camera.getName()
	            );
	        }

	    });
	}

	public Camera getConfig() {
		return this.camera;
	}
	
	public static void shutdownExecutors(){

	    RECONNECT_EXECUTOR.shutdownNow();


	    RELEASE_EXECUTOR.shutdown();

	    try {

	        if(!RELEASE_EXECUTOR.awaitTermination(5, TimeUnit.SECONDS)){
	            RELEASE_EXECUTOR.shutdownNow();
	        }

	    } catch (InterruptedException e) {

	        Thread.currentThread().interrupt();
	        RELEASE_EXECUTOR.shutdownNow();

	    }

	}

	private static void logDebug(String message) {
		if (DEBUG) {
			System.out.println(message);
		}
	}
}
