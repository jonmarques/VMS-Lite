package br.com.jonmarques.vmslite;
import javax.swing.*;

import com.formdev.flatlaf.FlatDarculaLaf;
import com.sun.jna.NativeLibrary;

import br.com.jonmarques.vmslite.entity.Camera;
import br.com.jonmarques.vmslite.entity.VMSConfig;
import br.com.jonmarques.vmslite.service.ConfigService;
import br.com.jonmarques.vmslite.service.OnvifDiscoveryService;
import uk.co.caprica.vlcj.binding.support.runtime.RuntimeUtil;

import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class VMSLite extends JFrame {

    /**
	 * */
	private static final long serialVersionUID = 1L;

	private static VMSLite instance;

	private JPanel camerasPanel;
	
    private final List<CameraPanel> cameras =
            new ArrayList<>();

    private final List<Camera> configs =
            new ArrayList<>();
    
    private VMSConfig vmsconfig;

    private boolean fullscreen = false;
    private Rectangle windowBounds;

    private JPanel topBar;
    private JButton btnFullscreen; // Botão que fica na topBar (modo janela)
    private JButton btnFullscreenGlass; // Botão que fica flutuando em tela cheia
    private JPanel glassPanel; // Painel transparente para o GlassPane

    boolean[][] ocupada;
    
    public VMSLite() {
   
		super("VMS Lite");
		
		System.setProperty("sun.java2d.d3d", "true");
	    System.setProperty("sun.java2d.ddforcevram", "true");
	    System.setProperty("swing.bufferPerWindow", "true");
	    System.setProperty("sun.java2d.noddraw", "true");

	    // ADICIONE ESTAS DUAS PARA CORRIGIR O MODO EMBEDDED:
	    // Impede que o Windows tente apagar o fundo do Canvas do VLC ao arrastar ou redimensionar a grade
	    System.setProperty("sun.awt.noerasebackground", "true");
	    System.setProperty("sun.awt.erasebackgroundonresize", "false");
	    
		String basePath = System.getProperty("user.dir");

		// 1. Caminho para quando estiver rodando dentro do Eclipse (Desenvolvimento)
		NativeLibrary.addSearchPath(
		    RuntimeUtil.getLibVlcLibraryName(),
		    basePath + "/vlc"
		);

		// 2. Caminho para quando estiver rodando pelo .exe do jpackage (Produção)
		NativeLibrary.addSearchPath(
		    RuntimeUtil.getLibVlcLibraryName(),
		    basePath + "/app/vlc"
		);
		
     	instance = this;
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(1400, 900);
        setLocationRelativeTo(null);
    	vmsconfig = ConfigService.load();
    	ocupada = new boolean[vmsconfig.getLayoutRows()][vmsconfig.getLayoutCols()];
    	
        createInterface();
        loadSavedCameras();

        setVisible(true);
    }

    public boolean isFullscreen() {
        return this.fullscreen;
    }

    public void reordenarCameras(CameraPanel origem, CameraPanel destino) {
        int indexOrigem = cameras.indexOf(origem);
        int indexDestino = cameras.indexOf(destino);

        if (indexOrigem != -1 && indexDestino != -1) {
            java.util.Collections.swap(cameras, indexOrigem, indexDestino);
            java.util.Collections.swap(configs, indexOrigem, indexDestino);
            saveConfigs();
        }
        rebuildLayout();
    }

    private void createInterface() {
        FlatDarculaLaf.setup();
        
        camerasPanel = new JPanel(null); // layout absoluto
        add(camerasPanel, BorderLayout.CENTER);

        camerasPanel.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                rebuildLayout();
            }
        });    	
        
     // --- Barra Superior (Modo Janela) ---
        topBar = new JPanel();
        // Alterado para BoxLayout no eixo X (Horizontal) para fazer a mola funcionar
        topBar.setLayout(new BoxLayout(topBar, BoxLayout.X_AXIS));
        // Adiciona uma pequena margem interna para os botões não colarem nas bordas da janela
        topBar.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        JButton addButton = new JButton("Adicionar Câmera");
        addButton.addActionListener(e -> addCameraDialog());
        topBar.add(addButton);
        topBar.add(Box.createHorizontalStrut(5)); // Espaçamento de 5px entre os botões

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

        JButton onvifButton = new JButton("Buscar ONVIF 🔍");
        topBar.add(onvifButton);
        onvifButton.addActionListener(e -> executarBuscaOnvif(onvifButton));

        // ====================================================================
        // MOLA HORIZONTAL: Ocupa todo o espaço restante e joga o próximo botão para a direita
        // ====================================================================
        topBar.add(Box.createHorizontalGlue());

        // Botão de Tela Cheia Destacado
        btnFullscreen = new JButton("Tela Cheia ⛶");
        btnFullscreen.setFocusable(false);
        btnFullscreen.setBackground(new Color(30, 144, 255)); // Azul Dodger
        btnFullscreen.setForeground(Color.WHITE);
        btnFullscreen.setBorderPainted(false);
        btnFullscreen.setFont(btnFullscreen.getFont().deriveFont(Font.BOLD));

        btnFullscreen.addActionListener(e -> toggleFullscreen());
        topBar.add(btnFullscreen); 

        add(topBar, BorderLayout.NORTH);

               
        // --- Configuração do Botão Flutuante (Modo Tela Cheia) ---
        setupGlassPaneForFullscreen();
    }
    
    private void setupGlassPaneForFullscreen() {
        // Criando o botão com o texto correto
        btnFullscreenGlass = new JButton("Sair Tela Cheia ⛶");
        btnFullscreenGlass.setFocusable(false);
        btnFullscreenGlass.setVisible(false); // Começa invisível, controlado pelo gerenciarBotaoJanela

        // --- Customização Visual (FlatLaf) ---
        btnFullscreenGlass.setBackground(new Color(220, 53, 69)); // Vermelho elegante (estilo Bootstrap/Danger)
        btnFullscreenGlass.setForeground(Color.WHITE);
        btnFullscreenGlass.setBorderPainted(false);
        btnFullscreenGlass.setFont(btnFullscreenGlass.getFont().deriveFont(Font.BOLD));
        btnFullscreenGlass.putClientProperty("JButton.buttonType", "roundRect"); // Bordas arredondadas

        btnFullscreenGlass.addActionListener(e -> toggleFullscreen());

        // Painel transparente por cima de toda a tela (Layout nulo para posicionamento absoluto)
        glassPanel = new JPanel(null);
        glassPanel.setOpaque(false);
        glassPanel.add(btnFullscreenGlass);

        setGlassPane(glassPanel);
    }

    public void gerenciarBotaoJanela(Point pontoNaTela) {
        if (!isFullscreen()) return;

        // Obtém a resolução atual do monitor onde a janela está aberta
        GraphicsConfiguration config = getGraphicsConfiguration();
        Rectangle bounds = config.getBounds();

        // ====================================================================
        // CÁLCULO PARA O CANTO DIREITO SUPERIOR
        // ====================================================================
        int larguraBotao = 140; // Defina a largura estimada do seu botão de fechar/sair
        int margemDireita = 20;  // Distância da borda direita da tela
        
        // X absoluto na tela: Largura total do monitor menos a largura do botão e a margem
        int xBotao = bounds.x + bounds.width - larguraBotao - margemDireita;
        int yBotao = bounds.y + 15; // 15px de distância do topo da tela

        // Se o mouse se aproximar do topo direito da tela (ex: nos primeiros 80 pixels de Y e após o X calculado)
        if (pontoNaTela.y < bounds.y + 80 && pontoNaTela.x > xBotao - 50) {
            // Exibe o botão de fechar na posição correta do canto direito
        	btnFullscreenGlass.setBounds(xBotao, yBotao, larguraBotao, 35);
        	btnFullscreenGlass.setVisible(true);
        } else {
            // Esconde o botão se o mouse se afastar daquela região
        	btnFullscreenGlass.setVisible(false);
        }
    }

    private void addCameraDialog() {
        JTextField nameField = new JTextField();
        JTextField urlField = new JTextField();
        JTextField linhas = new JTextField();
        JTextField colunas = new JTextField();
        
        Object[] fields = {
                "Nome:", nameField,
                "RTSP:", urlField,
                "Linhas:", linhas,
                "Colunas:", colunas
        };

        int result = JOptionPane.showConfirmDialog(
                        this, fields, "Nova câmera", JOptionPane.OK_CANCEL_OPTION
                );

        if (result == JOptionPane.OK_OPTION) {
            Camera config = new Camera(
                            nameField.getText(),
                            urlField.getText(), Integer.valueOf(linhas.getText()), Integer.valueOf(colunas.getText())
                    );
            addCamera(config);
            saveConfigs();
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

    private void addCamera(Camera config) {
        CameraPanel panel = new CameraPanel(this, config);

        cameras.add(panel);
        configs.add(config);
        camerasPanel.add(panel);

        rebuildLayout();
        
        SwingUtilities.invokeLater(() -> {
            if (panel.isDisplayable()) {
                panel.start();
            }
        });
    }

    private void loadSavedCameras() {
        List<Camera> saved = vmsconfig.getCameras();
        for (Camera config : saved) {
            addCamera(config);
        }
    }

    public void saveConfigs() {
    	VMSConfig config = new VMSConfig(
    	        vmsconfig.getLayoutCols(),
    	        vmsconfig.getLayoutRows(),
    	        configs
    	);

    	try {
			ConfigService.save(config);
		} catch (Exception e) {
			e.printStackTrace();
		}
    }

    public void removeCamera(CameraPanel panel) {
        cameras.remove(panel);
        configs.remove(panel.getConfig());
        camerasPanel.remove(panel);
        saveConfigs();

        camerasPanel.revalidate();
        camerasPanel.repaint();
    }
    
    private void importarConfig() {
        JFileChooser chooser = new JFileChooser();
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION)
            return;

        try {
            File selectedFile = chooser.getSelectedFile();
            VMSConfig config = ConfigService.loadFromFile(selectedFile);
            ConfigService.save(config);

            cameras.clear();
            configs.clear();
            camerasPanel.removeAll();

            for (Camera cam : config.getCameras()) {
            	CameraPanel panel = new CameraPanel(this, cam);
            	cameras.add(panel);
            	configs.add(cam);
            	camerasPanel.add(panel);
            }

            rebuildLayout();
        } catch (Exception ex) {
            ex.printStackTrace();
        }
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
        GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
        GraphicsDevice gd = ge.getDefaultScreenDevice();

        if (!fullscreen) {
            // --- ENTRANDO EM TELA CHEIA NATIVA ---
            windowBounds = getBounds(); 

            // 1. Remove a barra superior do layout Swing
            remove(topBar);

            // 2. Ativa o GlassPane superior para conter o botão flutuante
            getGlassPane().setVisible(true);
            btnFullscreenGlass.setVisible(false); 

            // 3. Método nativo alternador
            if (gd.isFullScreenSupported()) {
                gd.setFullScreenWindow(this);
            } else {
                setExtendedState(JFrame.MAXIMIZED_BOTH);
                setSize(Toolkit.getDefaultToolkit().getScreenSize());
                setVisible(true);
            }

            fullscreen = true;
        } else {
            // --- SAINDO DE TELA CHEIA NATIVA ---
            if (gd.isFullScreenSupported()) {
                gd.setFullScreenWindow(null);
            }
            
            setExtendedState(JFrame.NORMAL);
            setBounds(windowBounds);

            // Oculta a camada superior de controle
            getGlassPane().setVisible(false);

            // Devolve a barra superior de ferramentas
            add(topBar, BorderLayout.NORTH);

            fullscreen = false;
        }

        SwingUtilities.invokeLater(() -> {
            rebuildLayout();
            revalidate();
            repaint();
        });
    }
    
    public void rebuildLayout() {
        int rows = vmsconfig.getLayoutRows();
        int cols = vmsconfig.getLayoutCols();

        int gap = 4;
        int totalW = camerasPanel.getWidth();
        int totalH = camerasPanel.getHeight();

        if (totalW <= 0 || totalH <= 0)
            return;

        boolean[][] ocupado = new boolean[100][100];
        int maxRowUsada = rows;
        int maxColUsada = cols;

        java.util.Map<CameraPanel, Point> posicoes = new java.util.HashMap<>();
        
        for (CameraPanel panel : cameras) {
            Camera cam = panel.getConfig();

            int w = cam.getColSpan();
            int h = cam.getRowSpan();

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

            int w = cam.getColSpan();
            int h = cam.getRowSpan();

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
    	        config.setName(nameField.getText());
    	        config.setUrl(urlField.getText());
    	        config.setRowSpan(Integer.parseInt(linhasField.getText()));
    	        config.setColSpan(Integer.parseInt(colunasField.getText()));

    	        saveConfigs();

    	        if (panel.getConfig() != null) {
    	            panel.setArrastando(true); 
    	        }

    	        rebuildLayout();

    	        SwingUtilities.invokeLater(() -> {
    	            panel.setArrastando(false);
    	            panel.start(); 
    	        });
    	        
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

        // Executa o descobrimento em background
        OnvifDiscoveryService.discoverDevices(dispositivos -> {
            // CORREÇÃO CRÍTICA: Transfere a execução de volta para a EDT (Event Dispatch Thread) do Swing
            SwingUtilities.invokeLater(() -> {
                botaoMenu.setEnabled(true);
                botaoMenu.setText("Buscar ONVIF 🔍");

                java.util.Set<String> ipsConectados = new java.util.HashSet<>();
                if (this.configs != null) {
                    for (br.com.jonmarques.vmslite.entity.Camera cam : this.configs) {
                        java.util.regex.Matcher m = java.util.regex.Pattern.compile("@(\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3})")
                                .matcher(cam.getUrl());
                        if (m.find()) {
                            ipsConectados.add(m.group(1));
                        } else {
                            java.util.regex.Matcher mSemUser = java.util.regex.Pattern.compile("rtsp://(\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3})")
                                    .matcher(cam.getUrl());
                            if (mSemUser.find()) ipsConectados.add(mSemUser.group(1));
                        }
                    }
                }

                java.util.List<String> ipsFiltrados = new java.util.ArrayList<>();
                for (String ip : dispositivos.keySet()) {
                    if (!ipsConectados.contains(ip)) {
                        ipsFiltrados.add(ip);
                    }
                }

                // Ordenação dos IPs
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
                        // Fallback silencioso caso encontre um formato inesperado
                    }
                    return 0;
                });

                DefaultListModel<String> listModel = new DefaultListModel<>();
                for (String ip : ipsFiltrados) {
                    listModel.addElement(ip + "   - [" + dispositivos.get(ip) + "]");
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
                    java.awt.Component comp = (java.awt.Component) ev.getSource();
                    java.awt.Window win = SwingUtilities.getWindowAncestor(comp);
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

                    // Instancia o serviço ONVIF moderno que configuramos anteriormente
                    br.com.jonmarques.vmslite.service.OnvifDiscoveryService serviceOnvif = new br.com.jonmarques.vmslite.service.OnvifDiscoveryService();

                    for (String linha : linhasSelecionadas) {
                        String ip = linha.split(" ")[0].trim();
                        String modelo = dispositivos.get(ip);

                        JTextField userField = new JTextField("admin");
                        JTextField nameField = new JTextField(modelo + " (" + ip + ")");
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

                        Object[] loginFields = {
                            "Configurar acesso para o dispositivo:",
                            "IP: " + ip + " | Modelo: " + modelo,
                            "\nNome de Exibição no Layout:", nameField,
                            "Usuário da Câmera:", userField,
                            "Senha da Câmera:", passPanel,
                            "Perfil de Vídeo:", streamCombo
                        };

                        int loginOption = JOptionPane.showConfirmDialog(VMSLite.this, loginFields, 
                                "Autenticar: " + ip, JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);

                        if (loginOption == JOptionPane.OK_OPTION) {
                            String user = userField.getText().trim();
                            String pass = new String(passField.getPassword()).trim();
                            boolean isSubstream = streamCombo.getSelectedIndex() == 1; // 0 para Main, 1 para Sub
                            String nomeFinal = nameField.getText().trim();

                            // 1. Usa o XAddr que o OnvifDiscoveryService já nos forneceu (muito mais preciso que forçar porta 80)
                            String serviceUrl = dispositivos.get(ip); 

                            // 2. Chama o método melhorado passando a flag isSubstream
                            // O serviço se encarrega de encontrar o perfil correto agora
                            String rtspUrl = serviceOnvif.obterUrlRtsp(serviceUrl, user, pass, modelo, ip, isSubstream);

                            if (rtspUrl != null) {
                                br.com.jonmarques.vmslite.entity.Camera novaCam = 
                                        new br.com.jonmarques.vmslite.entity.Camera(nomeFinal, rtspUrl, 1, 1);
                                
                                addCamera(novaCam);
                            } else {
                                JOptionPane.showMessageDialog(VMSLite.this, 
                                    "Não foi possível obter a URL RTSP. Verifique usuário/senha.", 
                                    "Erro de Conexão", JOptionPane.ERROR_MESSAGE);
                            }
                        }
                    }
                    rebuildLayout();
                    saveConfigs();
                }
            });
        });
    }
    
    public static void main(String[] args) {
        SwingUtilities.invokeLater(VMSLite::new);
    }
    
    public static VMSLite getInstance() {
        return instance;
    }
}