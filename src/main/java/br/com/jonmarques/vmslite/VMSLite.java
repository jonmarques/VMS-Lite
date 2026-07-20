package br.com.jonmarques.vmslite;
import javax.swing.*;

import com.formdev.flatlaf.FlatDarculaLaf;
import com.sun.jna.NativeLibrary;

import br.com.jonmarques.vmslite.entity.Camera;
import br.com.jonmarques.vmslite.entity.VMSConfig;
import uk.co.caprica.vlcj.binding.support.runtime.RuntimeUtil;
import br.com.jonmarques.vmslite.service.ConfigService;
import br.com.jonmarques.vmslite.service.OnvifDiscoveryService;

import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class VMSLite extends JFrame {

	private static final long serialVersionUID = 1L;
	private static final boolean DEBUG = Boolean.getBoolean("vmslite.debug");

	private static VMSLite instance;

	private JPanel camerasPanel;

	private final List<CameraPanel> cameras = new ArrayList<>();

	private VMSConfig vmsconfig;

	private boolean fullscreen = false;
	private Rectangle windowBounds;

	private JPanel topBar;
	private JButton btnFullscreen; 
	private JButton btnFullscreenGlass; 
	private JPanel glassPanel; 

	private Timer resizeDebounce;

	// Gerenciadores do carregamento assíncrono
	private JPanel mainContainer;
	private CardLayout cardLayout;
	private JLabel lblStatus;

	public VMSLite() {

		super("VMS Lite");

	    if (!SingleInstance.lock()) {
	        System.exit(0);
	    }

	    Runtime.getRuntime().addShutdownHook(new Thread(SingleInstance::unlock));
		
		setSize(1400, 900);
		setLocationRelativeTo(null);

		// 1. Cria a interface estrutural com a tela de "Carregando..." ativa
		createInterface();

		// 2. Torna a janela visível imediatamente para exibir o feedback visual
		setVisible(true);
	    
		String exePath = System.getProperty("user.dir") + "\\VMSLite.exe";
		addToStartup("VMSLite", exePath);
		
		System.setProperty("sun.java2d.opengl", "true");
		System.setProperty("swing.bufferPerWindow", "true");
		System.setProperty("sun.java2d.noddraw", "true");
		System.setProperty("sun.awt.noerasebackground", "true");
		System.setProperty("sun.awt.erasebackgroundonresize", "false");

		String basePath = System.getProperty("user.dir");

		NativeLibrary.addSearchPath(
				RuntimeUtil.getLibVlcLibraryName(),
				basePath + "/vlc"
				);

		NativeLibrary.addSearchPath(
				RuntimeUtil.getLibVlcLibraryName(),
				basePath + "/app/vlc"
				);

		instance = this;

		setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
		addWindowListener(new java.awt.event.WindowAdapter() {
			@Override
			public void windowClosing(java.awt.event.WindowEvent e) {
				new Thread(() -> {

				    List<Future<?>> tasks = new ArrayList<>();


				    for(CameraPanel panel : cameras){

				        Future<?> f = panel.stop();

				        if(f != null)
				            tasks.add(f);
				    }


				    for(Future<?> f : tasks){

				        try {

				            f.get(3, TimeUnit.SECONDS);

				        } catch(Exception e2){

				            System.err.println(
				                "Timeout liberando câmera"
				            );
				        }
				    }


				    VlcManager.shutdown();


				    SwingUtilities.invokeLater(() -> {

				        dispose();

				        System.exit(0);

				    });


				}).start();
			}
		});


		
		vmsconfig = ConfigService.load();

		VlcManager.init();

		// 3. Processa e popula as câmeras em background sem congelar o visual
		new Thread(() -> {
			loadSavedCameras(); 

			// Com tudo criado na memória e o layout calculado, fazemos a transição na EDT

		}).start();

		toggleFullscreen();
		
		AppWatchdog.start();
		ExternalWatchdogInstaller.install();
	}

	public boolean isFullscreen() {
		return this.fullscreen;
	}

	public void reordenarCameras(CameraPanel origem, CameraPanel destino) {
		int indexOrigem = cameras.indexOf(origem);
		int indexDestino = cameras.indexOf(destino);

		if (indexOrigem != -1 && indexDestino != -1) {
			Collections.swap(cameras, indexOrigem, indexDestino);
			Collections.swap(vmsconfig.getCameras(), indexOrigem, indexDestino);
			saveConfigs();
		}
		rebuildLayout();
	}

	private void createInterface() {
		FlatDarculaLaf.setup();

		cardLayout = new CardLayout();
		mainContainer = new JPanel(cardLayout);

		// --- Tela de Carregando ---
		JPanel loadingScreen = new JPanel(new GridBagLayout());
		loadingScreen.setBackground(new Color(30, 30, 30));
		lblStatus = new JLabel("Inicializando motores de vídeo...", SwingConstants.CENTER);
		lblStatus.setFont(new Font("Segoe UI", Font.BOLD, 16));
		lblStatus.setForeground(Color.WHITE);

		JProgressBar progressBar = new JProgressBar();
		progressBar.setIndeterminate(true); 
		progressBar.setPreferredSize(new Dimension(300, 15));

		GridBagConstraints gbc = new GridBagConstraints();
		gbc.gridx = 0; gbc.gridy = 0; gbc.insets = new Insets(10,10,10,10);
		loadingScreen.add(lblStatus, gbc);
		gbc.gridy = 1;
		loadingScreen.add(progressBar, gbc);

		// --- Painel Real das Câmeras ---
		camerasPanel = new JPanel(null); 

		mainContainer.add(loadingScreen, "LOADING");
		mainContainer.add(camerasPanel, "CAMERAS");

		// Adiciona o container gerenciador no centro do Frame
		add(mainContainer, BorderLayout.CENTER);
		cardLayout.show(mainContainer, "LOADING");

		camerasPanel.addComponentListener(new ComponentAdapter() {
			@Override
			public void componentResized(ComponentEvent e) {
				if (resizeDebounce != null) {
					resizeDebounce.stop();
				}
				resizeDebounce = new Timer(80, ev -> rebuildLayout());
				resizeDebounce.setRepeats(false);
				resizeDebounce.start();
			}
		});

		// --- Barra Superior ---
		topBar = new JPanel();
		topBar.setLayout(new BoxLayout(topBar, BoxLayout.X_AXIS));
		topBar.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

		JButton onvifButton = new JButton("Buscar ONVIF 🔍");
		topBar.add(onvifButton);
		onvifButton.addActionListener(e -> executarBuscaOnvif(onvifButton));

		JButton gridButton = new JButton("Layout");
		gridButton.addActionListener(e -> showGridDialog());
		topBar.add(gridButton);
		topBar.add(Box.createHorizontalStrut(5));

		JButton importButton = new JButton("Importar");
		importButton.addActionListener(e -> importarConfig());
		topBar.add(importButton);
		topBar.add(Box.createHorizontalStrut(5));

		JButton exportButton = new JButton("Exportar");
		exportButton.addActionListener(e -> exportarConfig());
		topBar.add(exportButton);
		topBar.add(Box.createHorizontalStrut(5));

		topBar.add(Box.createHorizontalGlue());

		btnFullscreen = new JButton("Tela Cheia ⛶");
		btnFullscreen.setFocusable(false);
		btnFullscreen.setBackground(new Color(30, 144, 255));
		btnFullscreen.setForeground(Color.WHITE);
		btnFullscreen.setBorderPainted(false);
		btnFullscreen.setFont(btnFullscreen.getFont().deriveFont(Font.BOLD));

		btnFullscreen.addActionListener(e -> toggleFullscreen());
		topBar.add(btnFullscreen); 

		add(topBar, BorderLayout.NORTH);

		setupGlassPaneForFullscreen();
	}

	private void setupGlassPaneForFullscreen() {
		btnFullscreenGlass = new JButton("Sair Tela Cheia ⛶");
		btnFullscreenGlass.setFocusable(false);
		btnFullscreenGlass.setVisible(false);

		btnFullscreenGlass.setBackground(new Color(220, 53, 69));
		btnFullscreenGlass.setForeground(Color.WHITE);
		btnFullscreenGlass.setBorderPainted(false);
		btnFullscreenGlass.setFont(btnFullscreenGlass.getFont().deriveFont(Font.BOLD));
		btnFullscreenGlass.putClientProperty("JButton.buttonType", "roundRect");

		btnFullscreenGlass.addActionListener(e -> toggleFullscreen());

		glassPanel = new JPanel(null);
		glassPanel.setOpaque(false);
		glassPanel.add(btnFullscreenGlass);

		setGlassPane(glassPanel);
	}

	public void gerenciarBotaoJanela(Point pontoNaTela) {
		if (!isFullscreen()) return;

		GraphicsConfiguration config = getGraphicsConfiguration();
		Rectangle bounds = config.getBounds();

		int larguraBotao = 140;
		int margemDireita = 20;

		int xBotao = bounds.x + bounds.width - larguraBotao - margemDireita;
		int yBotao = bounds.y + 15;

		if (pontoNaTela.y < bounds.y + 80 && pontoNaTela.x > xBotao - 50) {
			btnFullscreenGlass.setBounds(xBotao, yBotao, larguraBotao, 35);
			btnFullscreenGlass.setVisible(true);
		} else {
			btnFullscreenGlass.setVisible(false);
		}
	}

	private void showGridDialog() {
		JSpinner rows = new JSpinner(new SpinnerNumberModel(vmsconfig.getLayoutRows(), 1, 20, 1));
		JSpinner cols = new JSpinner(new SpinnerNumberModel(vmsconfig.getLayoutCols(), 1, 20, 1));

		Object[] fields = {
				"Linhas:", rows,
				"Colunas:", cols
		};

		int result = JOptionPane.showConfirmDialog(this, fields, "Configurar Grid", JOptionPane.OK_CANCEL_OPTION);

		if(result == JOptionPane.OK_OPTION) {
			vmsconfig.setLayoutRows((Integer) rows.getValue());
			vmsconfig.setLayoutCols((Integer) cols.getValue());

			saveConfigs();
			rebuildLayout();        
		}
	}

	private CameraPanel addCameraPanel(Camera config, boolean deferLayout) {
		if (!SwingUtilities.isEventDispatchThread()) {
			final CameraPanel[] panel = new CameraPanel[1];
			try {
				SwingUtilities.invokeAndWait(() -> panel[0] = addCameraPanel(config, deferLayout));
			} catch (Exception e) {
				throw new IllegalStateException("Erro ao adicionar camera na interface", e);
			}
			return panel[0];
		}

		CameraPanel panel = new CameraPanel(this, config);

		cameras.add(panel);
		if (!vmsconfig.getCameras().contains(config)) {
			vmsconfig.getCameras().add(config);
		}
		camerasPanel.add(panel);

		if (!deferLayout) {
			rebuildLayout();
		}
		return panel;
	}

	private void startCamerasSequentially() {
		startCamerasSequentially(0);
	}

	private void startCamerasSequentially(int fromIndex) {
		final int[] index = {fromIndex};

		Timer sequentialOpener = new Timer(250, null);
		sequentialOpener.addActionListener(e -> {
			if (index[0] < cameras.size()) {
				cameras.get(index[0]).start();
				index[0]++;
			} else {
				sequentialOpener.stop();
			}
		});
		sequentialOpener.start();
	}

	private boolean aplicarAtualizacoesOnvif(List<Camera> cameraList, Map<String, OnvifDiscoveryService.DeviceInfo> dispositivos) {
		boolean necessarioSalvar = false;
		Set<Camera> urlAlteradas = new HashSet<>();

		for (Camera config : cameraList) {
			String ip = OnvifDiscoveryService.extrairIpDaUrl(config.getUrl());

			if ((config.getUuid() == null || config.getUuid().isBlank()) && ip != null && dispositivos.containsKey(ip)) {
				String uuidAtual = dispositivos.get(ip).getUuid();
				if (uuidAtual != null) {
					config.setUuid(uuidAtual);
					logDebug("UUID ONVIF salvo para '" + config.getName() + "'.");
					necessarioSalvar = true;
				}
			}

			boolean ipAindaAtivo = ip != null && dispositivos.containsKey(ip);

			if (!ipAindaAtivo && config.getUuid() != null && !config.getUuid().isBlank()) {
				String ipCandidato = OnvifDiscoveryService.encontrarIpPorUuid(dispositivos, config.getUuid());

				if (ipCandidato != null) {
					String novoUrl = OnvifDiscoveryService.substituirIpNaUrl(config.getUrl(), ipCandidato);
					config.setUrl(novoUrl);
					urlAlteradas.add(config);
					logDebug("IP da camera '" + config.getName() + "' alterado para " + ipCandidato + " (via UUID)");
					necessarioSalvar = true;
				}
			}
		}

		if (!urlAlteradas.isEmpty()) {
			reiniciarCamerasComUrlAlterada(urlAlteradas);
		}

		return necessarioSalvar;
	}

	private void reiniciarCamerasComUrlAlterada(Set<Camera> alteradas) {
		SwingUtilities.invokeLater(() -> {
			for (CameraPanel panel : cameras) {
				if (alteradas.contains(panel.getConfig())) {
					panel.start();
				}
			}
		});
	}

	private void loadSavedCameras() {
		List<Camera> saved = new ArrayList<>(vmsconfig.getCameras());

		SwingUtilities.invokeLater(() -> lblStatus.setText("Montando grade de câmeras..."));

		for (Camera config : saved) {
			logDebug("Camera preparada: " + config.getUrl() + " uuid: " + config.getUuid());
			addCameraPanel(config, true);
		}

		SwingUtilities.invokeLater(() -> {
			lblStatus.setText("Carregando video das cameras...");
			rebuildLayout();
			cardLayout.show(mainContainer, "CAMERAS");
			startCamerasSequentially();
		});

		// ONVIF roda em paralelo — não bloqueia a exibição do vídeo
		OnvifDiscoveryService.discoverDevices(dispositivos -> {
			boolean necessarioSalvar = aplicarAtualizacoesOnvif(saved, dispositivos);
			if (necessarioSalvar) {
				saveConfigs();
			}
		});
	}

	public void saveConfigs() {
		VMSConfig config = new VMSConfig(
				vmsconfig.getLayoutCols(),
				vmsconfig.getLayoutRows(),
				vmsconfig.getCameras()
				);

		try {
			ConfigService.save(config);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	public void removeCamera(CameraPanel panel) {
		cameras.remove(panel);
		vmsconfig.getCameras().remove(panel.getConfig());
		camerasPanel.remove(panel);
		saveConfigs();

		camerasPanel.revalidate();
		camerasPanel.repaint();
	}

	private void importarConfig() {
	    JFileChooser chooser = new JFileChooser();
	    if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION)
	        return;
	    cardLayout.show(mainContainer, "LOADING");
	    lblStatus.setText("Importando configuração...");

	    new Thread(() -> {
	        try {
	            File selectedFile = chooser.getSelectedFile();
	            VMSConfig config = ConfigService.loadFromFile(selectedFile);
	            ConfigService.save(config);

	            SwingUtilities.invokeAndWait(() -> {
	                for (CameraPanel oldPanel : cameras) {
	                    oldPanel.stop();
	                }

	                cameras.clear();
	                vmsconfig.getCameras().clear();
	                camerasPanel.removeAll();
	            });

	            vmsconfig.setLayoutRows(config.getLayoutRows());
	            vmsconfig.setLayoutCols(config.getLayoutCols());

	            List<Camera> importadas = new ArrayList<>(config.getCameras());

	            SwingUtilities.invokeLater(() -> lblStatus.setText("Montando grade importada..."));

	            for (Camera cam : importadas) {
	                addCameraPanel(cam, true);
	            }

	            SwingUtilities.invokeLater(() -> {
	                lblStatus.setText("Carregando video das cameras...");
	                rebuildLayout();
	                cardLayout.show(mainContainer, "CAMERAS");
	                startCamerasSequentially();
	            });

	            // ONVIF em paralelo — mesmo fluxo do startup
	            OnvifDiscoveryService.discoverDevices(dispositivos -> {
	                boolean necessarioSalvar = aplicarAtualizacoesOnvif(importadas, dispositivos);
	                if (necessarioSalvar) {
	                    saveConfigs();
	                }
	            });
	        } catch (Exception ex) {
	            ex.printStackTrace();
	            SwingUtilities.invokeLater(() ->
	                    JOptionPane.showMessageDialog(this, "Erro ao importar configuração.", "Erro", JOptionPane.ERROR_MESSAGE));
	        } finally {
	            SwingUtilities.invokeLater(() -> {
	                rebuildLayout();
	                cardLayout.show(mainContainer, "CAMERAS");
	            });
	        }
	    }).start();
	}

	private void exportarConfig() {
		JFileChooser chooser = new JFileChooser();
		chooser.setSelectedFile(new File("vms-backup.json"));

		int result = chooser.showSaveDialog(this);
		if (result != JFileChooser.APPROVE_OPTION) return;

		File file = chooser.getSelectedFile();
		try {
			ConfigService.saveToFile(vmsconfig, file);
			JOptionPane.showMessageDialog(this, "Exportado com sucesso!");
		} catch (Exception ex) {
			ex.printStackTrace();
			JOptionPane.showMessageDialog(this, "Erro ao exportar config");
		}
	}

	private void toggleFullscreen() {
		if (!fullscreen) {
			windowBounds = getBounds();

			remove(topBar);
			getGlassPane().setVisible(true);
			btnFullscreenGlass.setVisible(false);

			GraphicsConfiguration gc = getGraphicsConfiguration();
			Rectangle screenBounds = (gc != null)
					? gc.getBounds()
					: new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());

			dispose();
			setUndecorated(true);
			setBounds(screenBounds);
			setVisible(true);

			fullscreen = true;
		} else {
			dispose();
			setUndecorated(false);
			setBounds(windowBounds);

			getGlassPane().setVisible(false);
			add(topBar, BorderLayout.NORTH);

			setVisible(true);

			fullscreen = false;
		}

		revalidate();
		repaint();
	}

	public void rebuildLayout() {
		int rows = vmsconfig.getLayoutRows();
		int cols = vmsconfig.getLayoutCols();

		int gap = 4;
		int totalW = camerasPanel.getWidth();
		int totalH = camerasPanel.getHeight();

		if (totalW <= 0 || totalH <= 0)
			return;

		// Otimização dinâmica de tamanho de matriz sugerida anteriormente
		int totalRowSpan = 0;
		int maxColSpan = cols;
		for (CameraPanel panel : cameras) {
			Camera cam = panel.getConfig();
			totalRowSpan += Math.max(1, cam.getRowSpan());
			maxColSpan = Math.max(maxColSpan, Math.max(1, cam.getColSpan()));
		}

		int maxGridRows = Math.max(rows, totalRowSpan + rows);
		int maxGridCols = Math.max(cols, maxColSpan);
		boolean[][] ocupado = new boolean[maxGridRows][maxGridCols];

		int maxRowUsada = rows;
		int maxColUsada = cols;

		Map<CameraPanel, Point> posicoes = new HashMap<>();

		for (CameraPanel panel : cameras) {
			Camera cam = panel.getConfig();

			int w = Math.max(1, Math.min(cam.getColSpan(), maxGridCols));
			int h = Math.max(1, cam.getRowSpan());

			Point p = findPositionDinamico(ocupado, cols, w, h);
			posicoes.put(panel, p);

			for (int yy = 0; yy < h; yy++) {
				for (int xx = 0; xx < w; xx++) {
					ocupado[p.y + yy][p.x + xx] = true;
				}
			}

			if (p.y + h > maxRowUsada) maxRowUsada = p.y + h;
			if (p.x + w > maxColUsada) maxColUsada = p.x + w;
		}

		int cellW = (totalW - (maxColUsada - 1) * gap) / maxColUsada;
		int cellH = (totalH - (maxRowUsada - 1) * gap) / maxRowUsada;

		for (CameraPanel panel : cameras) {
			Camera cam = panel.getConfig();
			Point p = posicoes.get(panel);

			int w = Math.max(1, Math.min(cam.getColSpan(), maxGridCols));
			int h = Math.max(1, cam.getRowSpan());

			int x = p.x * (cellW + gap);
			int y = p.y * (cellH + gap);

			int width = w * cellW + (w - 1) * gap;
			int height = h * cellH + (h - 1) * gap;

			panel.setBounds(x, y, width, height);
		}

		camerasPanel.revalidate();
		camerasPanel.repaint();
	}

	private Point findPositionDinamico(boolean[][] grid, int maxCols, int w, int h) {
		if (w > maxCols) w = maxCols; 

		for (int y = 0; y < grid.length - h; y++) {
			for (int x = 0; x <= maxCols - w; x++) {
				boolean espacoLivre = true;

				for (int yy = 0; yy < h; yy++) {
					for (int xx = 0; xx < w; xx++) {
						if (grid[y + yy][x + xx]) {
							espacoLivre = false;
							break;
						}
					}
					if (!espacoLivre) break;
				}

				if (espacoLivre) {
					return new Point(x, y);
				}
			}
		}
		return new Point(0, 0); 
	}

	public void showEditCameraDialog(CameraPanel panel) {
		Camera config = panel.getConfig();

		JTextField nameField = new JTextField(config.getName());
		JTextField urlField = new JTextField(config.getUrl());
		JTextField linhasField = new JTextField(String.valueOf(config.getRowSpan()));
		JTextField colunasField = new JTextField(String.valueOf(config.getColSpan()));

		Object[] fields = {
				"Nome:", nameField,
				"RTSP URL:", urlField,
				"Linhas (Row Span):", linhasField,
				"Colunas (Col Span):", colunasField
		};

		Object[] options = {"Salvar", "Excluir Câmera", "Cancelar"};

		int result = JOptionPane.showOptionDialog(
				this, fields, "Editar Câmera: " + config.getName(), 
				JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE, null, options, options[0]
				);

		if (result == 0) { 

			    try {
			        String oldUrl = config.getUrl();
			        String newUrl = urlField.getText().trim();
			        config.setName(nameField.getText());
			        config.setUrl(newUrl);
			        config.setRowSpan(Math.max(1, Integer.parseInt(linhasField.getText().trim())));
			        config.setColSpan(Math.max(1, Integer.parseInt(colunasField.getText().trim())));

			        saveConfigs();

			        if (!newUrl.equals(oldUrl)) {
			        	
			            panel.setArrastando(true);
			            SwingUtilities.invokeLater(() -> {
			                panel.setArrastando(false);
			                panel.start();
			            });
			        }

			        rebuildLayout();
			        
			        String newIp = OnvifDiscoveryService.extrairIpDaUrl(newUrl);
			        String oldIp = OnvifDiscoveryService.extrairIpDaUrl(oldUrl);
			        if (newIp != null && oldIp != null && !newIp.equals(oldIp)) {
			        	logDebug("IP " + oldIp + " foi editado para " + newIp + ", setando UUID para null.");
			        	config.setUuid(null);
			        	saveConfigs();
			        }

			    } catch (NumberFormatException ex) {
			        JOptionPane.showMessageDialog(this,
			                "Por favor, insira números válidos para linhas e colunas.",
			                "Erro de Validação", JOptionPane.ERROR_MESSAGE);
			    }
		} else if (result == 1) {
			int confirmar = JOptionPane.showConfirmDialog(
					this, "Tem certeza que deseja remover a câmera \"" + config.getName() + "\"?",
					"Confirmar Exclusão", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE
					);

			if (confirmar == JOptionPane.YES_OPTION) {
				panel.stop(); 
				removeCamera(panel); 
				rebuildLayout(); 
			}
		}
	}

	private void executarBuscaOnvif(JButton botaoMenu) {
		botaoMenu.setEnabled(false);
		botaoMenu.setText("Escaneando Rede...");

		JDialog loadingDialog = new JDialog(this, "Aguarde", Dialog.ModalityType.MODELESS);
		loadingDialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
		loadingDialog.setResizable(false);

		JPanel panel = new JPanel(new BorderLayout(10, 10));
		panel.setBorder(BorderFactory.createEmptyBorder(15, 20, 15, 20));

		JProgressBar progress = new JProgressBar();
		progress.setIndeterminate(true);

		panel.add(new JLabel("Buscando câmeras ONVIF..."), BorderLayout.NORTH);
		panel.add(progress, BorderLayout.CENTER);

		loadingDialog.setContentPane(panel);
		loadingDialog.pack();
		loadingDialog.setLocationRelativeTo(this);
		loadingDialog.setVisible(true);

		OnvifDiscoveryService.discoverDevices(true, dispositivos -> {
			SwingUtilities.invokeLater(() -> {

				loadingDialog.dispose();

				botaoMenu.setEnabled(true);
				botaoMenu.setText("Buscar ONVIF 🔍");
				Set<String> ipsConectados = new HashSet<>();
				if (vmsconfig.getCameras() != null) {
					for (Camera cam : vmsconfig.getCameras()) {
						Matcher m = Pattern.compile("@(\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3})")
								.matcher(cam.getUrl());
						if (m.find()) {
							ipsConectados.add(m.group(1));
						} else {
							Matcher mSemUser = Pattern.compile("rtsp://(\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3})")
									.matcher(cam.getUrl());
							if (mSemUser.find()) ipsConectados.add(mSemUser.group(1));
						}
					}
				}

				List<String> ipsFiltrados = new ArrayList<>();
				for (String ip : dispositivos.keySet()) {
					if (!ipsConectados.contains(ip)) {
						ipsFiltrados.add(ip);
					}
				}
				
				

				ipsFiltrados.sort((ip1, ip2) -> {
					try {
						String[] parts1 = ip1.split("\\.");
						String[] parts2 = ip2.split("\\.");
						for (int i = 0; i < 4; i++) {
							int part1 = Integer.parseInt(parts1[i]);
							int part2 = Integer.parseInt(parts2[i]);
							if (part1 != part2) return Integer.compare(part1, part2);
						}
					} catch (Exception e) {
					}
					return 0;
				});


				DefaultListModel<String> listModel = new DefaultListModel<>();
				for (String ip : ipsFiltrados) {
				    String uuid = dispositivos.get(ip).getUuid();
				    String uuidExibicao = (uuid == null || uuid.isBlank()) ? "Sem UUID Visível" : uuid;

				    listModel.addElement(ip + "  - [" + uuidExibicao + "]");
				}

				JList<String> deviceList = new JList<>(listModel);
				deviceList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
				JScrollPane scrollPane = new JScrollPane(deviceList);
				scrollPane.setPreferredSize(new Dimension(380, 160));

				JPanel painelJanela = new JPanel(new BorderLayout(0, 8));
				JPanel topoPainel = new JPanel(new BorderLayout());
				JLabel labelInfo = new JLabel("Selecione as câmeras que deseja adicionar:");
				JButton btnRecarregar = new JButton("Escanear Novamente 🔄");
				btnRecarregar.setFocusable(false);

				topoPainel.add(labelInfo, BorderLayout.WEST);
				topoPainel.add(btnRecarregar, BorderLayout.EAST);

				painelJanela.add(topoPainel, BorderLayout.NORTH);
				painelJanela.add(scrollPane, BorderLayout.CENTER);
				painelJanela.add(new JLabel("💡 Dica: Segure CTRL para selecionar múltiplas câmeras."), BorderLayout.SOUTH);

				btnRecarregar.addActionListener(ev -> {
					Component comp = (Component) ev.getSource();
					Window win = SwingUtilities.getWindowAncestor(comp);
					if (win != null) win.dispose();
					executarBuscaOnvif(botaoMenu);
				});

				if (ipsFiltrados.isEmpty()) {
					listModel.addElement("Nenhum dispositivo novo encontrado.");
					deviceList.setEnabled(false);
				}

				int option = JOptionPane.showConfirmDialog(VMSLite.this, painelJanela, 
						"Câmeras ONVIF Descobertas", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);

				if (option == JOptionPane.OK_OPTION && deviceList.isEnabled()) {
					List<String> linhasSelecionadas = deviceList.getSelectedValuesList();
					if (linhasSelecionadas.isEmpty()) return;

					OnvifDiscoveryService serviceOnvif = new OnvifDiscoveryService();
					int indiceInicial = cameras.size();

					for (String lambdaLinha : linhasSelecionadas) {
					    String ip = lambdaLinha.split(" ")[0].trim();
					    String uuid = dispositivos.get(ip).getUuid();
					    String modelo = dispositivos.get(ip).getXaddr();

						JTextField userField = new JTextField("admin");
						JTextField nameField = new JTextField(modelo + " (" + ip + ")");

						// Novos campos para a dimensão do Grid (com valor padrão "1")
						JTextField linhasField = new JTextField("1");
						JTextField colunasField = new JTextField("1");

						String[] streams = {"Mainstream (Alta Resolução)", "Substream (Leve/Fluido)"};
						JComboBox<String> streamCombo = new JComboBox<>(streams);

						JPasswordField passField = new JPasswordField();
						passField.setEchoChar('•');
						JButton togglePassButton = new JButton("👁");
						togglePassButton.setPreferredSize(new Dimension(45, 20));
						togglePassButton.setFocusable(false);
						togglePassButton.addActionListener(txtEv -> {
							if (passField.getEchoChar() == (char) 0) {
								passField.setEchoChar('•');
								togglePassButton.setText("👁");
							} else {
								passField.setEchoChar((char) 0);
								togglePassButton.setText("🔒");
							}
						});

						JPanel passPanel = new JPanel(new BorderLayout(5, 0));
						passPanel.add(passField, BorderLayout.CENTER);
						passPanel.add(togglePassButton, BorderLayout.EAST);

						// Inclusão dos campos na interface do diálogo
						Object[] loginFields = {
								"Configurar acesso para o dispositivo:",
								"IP: " + ip + " | Modelo: " + modelo,
								"\nNome de Exibição no Layout:", nameField,
								"Usuário da Câmera:", userField,
								"Senha da Câmera:", passPanel,
								"Perfil de Vídeo:", streamCombo,
								"Linhas (Row Span):", linhasField,
								"Colunas (Col Span):", colunasField
						};

						int loginOption = JOptionPane.showConfirmDialog(VMSLite.this, loginFields, 
								"Autenticar: " + ip, JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);

						if (loginOption == JOptionPane.OK_OPTION) {
							String user = userField.getText().trim();
							String pass = new String(passField.getPassword()).trim();
							boolean isSubstream = streamCombo.getSelectedIndex() == 1;
							String nomeFinal = nameField.getText().trim();

							// Tratamento seguro da conversão de texto para inteiro
							int rSpan = 1;
							int cSpan = 1;
							try {
								rSpan = linhasField.getText().trim().isEmpty() ? 1 : Integer.parseInt(linhasField.getText().trim());
								cSpan = colunasField.getText().trim().isEmpty() ? 1 : Integer.parseInt(colunasField.getText().trim());
							} catch (NumberFormatException ex) {
								// Caso o usuário digite letras, o sistema assume 1x1 silenciosamente para evitar travar o loop
							}

							String serviceUrl = dispositivos.get(ip).getXaddr();
							String rtspUrl = serviceOnvif.obterUrlRtsp(serviceUrl, user, pass, modelo, ip, isSubstream);

							if (rtspUrl != null) {
					            Camera novaCam = new Camera(nomeFinal, rtspUrl, uuid, rSpan, cSpan);
					            addCameraPanel(novaCam, true);
					        } else {
					            JOptionPane.showMessageDialog(VMSLite.this,
					                    "Não foi possível obter a URL RTSP. Verifique usuário/senha.",
					                    "Erro de Conexão", JOptionPane.ERROR_MESSAGE);
					        }
						}
					}
					rebuildLayout();
					startCamerasSequentially(indiceInicial);
					saveConfigs();
				}
			});
		});
	}

	public static void addToStartup(String appName, String exePath) {
		try {
			ProcessBuilder check = new ProcessBuilder(
					"reg",
					"query",
					"HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run",
					"/v",
					appName
					);

			Process p = check.start();
			int result = p.waitFor();

			if (result == 0) {
				return; // já existe
			}

			ProcessBuilder add = new ProcessBuilder(
					"reg",
					"add",
					"HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run",
					"/v", appName,
					"/t", "REG_SZ",
					"/d", exePath,
					"/f"
					);

			add.start().waitFor();
			logDebug("Adicionado ao Startup!");

		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	private static void logDebug(String message) {
		if (DEBUG) {
			System.out.println(message);
		}
	}

	public static void main(String[] args) {
		SwingUtilities.invokeLater(VMSLite::new);
	}

	public static VMSLite getInstance() {
		return instance;
	}
}
