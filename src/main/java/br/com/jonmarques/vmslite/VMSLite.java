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
import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class VMSLite extends JFrame {

    private static final long serialVersionUID = 1L;

    private static VMSLite instance;

    private JPanel camerasPanel;
    
    private final List<CameraPanel> cameras = new ArrayList<>();
    private final List<Camera> configs = new ArrayList<>();
    
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
    
    public VMSLite() {
   
        super("VMS Lite");
        
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
                for (CameraPanel panel : cameras) {
                    panel.stop();
                }
                
                new Thread(() -> {
                    try {
                        Thread.sleep(300);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                    SwingUtilities.invokeLater(() -> {
                        dispose();
                        System.exit(0);
                    });
                }).start();
            }
        });

        setSize(1400, 900);
        setLocationRelativeTo(null);
        vmsconfig = ConfigService.load();
        
        // 1. Cria a interface estrutural com a tela de "Carregando..." ativa
        createInterface();

        // 2. Torna a janela visível imediatamente para exibir o feedback visual
        setVisible(true);
        
        // 3. Processa e popula as câmeras em background sem congelar o visual
        new Thread(() -> {
            loadSavedCameras(); 
            
            // Com tudo criado na memória e o layout calculado, fazemos a transição na EDT
            SwingUtilities.invokeLater(() -> {
                cardLayout.show(mainContainer, "CAMERAS");
            });
        }).start();
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
        
        cardLayout = new CardLayout();
        mainContainer = new JPanel(cardLayout);
        
        // --- Tela de Carregando ---
        JPanel loadingScreen = new JPanel(new GridBagLayout());
        loadingScreen.setBackground(new Color(30, 30, 30));
        JLabel lblStatus = new JLabel("Inicializando motores de vídeo...", SwingConstants.CENTER);
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

        JButton addButton = new JButton("Adicionar Câmera");
        addButton.addActionListener(e -> addCameraDialog());
        topBar.add(addButton);
        topBar.add(Box.createHorizontalStrut(5));

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
            try {
                int rSpan = linhas.getText().trim().isEmpty() ? 1 : Integer.parseInt(linhas.getText().trim());
                int cSpan = colunas.getText().trim().isEmpty() ? 1 : Integer.parseInt(colunas.getText().trim());
                
                Camera config = new Camera(nameField.getText(), urlField.getText(), rSpan, cSpan);
                addCamera(config);
                saveConfigs();
            } catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(this, 
                        "Por favor, insira números válidos para linhas e colunas.", 
                        "Erro de Validação", JOptionPane.ERROR_MESSAGE);
            }
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
        
        // Adiciona os painéis de forma síncrona dentro da thread paralela
        for (Camera config : saved) {
            CameraPanel panel = new CameraPanel(this, config);
            cameras.add(panel);
            configs.add(config);
            
            // Adições estruturais de componentes Swing precisam ir para o escopo visual com segurança
            SwingUtilities.invokeLater(() -> camerasPanel.add(panel));
        }
        
        // Força a matemática do layout rodar com base nas dimensões atuais calculadas
        SwingUtilities.invokeLater(this::rebuildLayout);
        
        // Inicialização escalonada e suave (Modo Inteligente rodando na EDT)
        SwingUtilities.invokeLater(() -> {
            final int[] index = {0};
            Timer sequentialOpener = new Timer(250, null); 
            sequentialOpener.addActionListener(e -> {
                if (index[0] < cameras.size()) {
                    CameraPanel panel = cameras.get(index[0]);
                    panel.start();
                    index[0]++;
                } else {
                    sequentialOpener.stop(); 
                }
            });
            sequentialOpener.start();
        });
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

            for (CameraPanel oldPanel : cameras) {
                oldPanel.stop();
            }

            cameras.clear();
            configs.clear();
            camerasPanel.removeAll();

            vmsconfig.setLayoutRows(config.getLayoutRows());
            vmsconfig.setLayoutCols(config.getLayoutCols());

            for (Camera cam : config.getCameras()) {
                addCamera(cam);
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
        int maxGridRows = Math.max(100, rows + 20);
        int maxGridCols = Math.max(100, cols + 20);
        boolean[][] ocupado = new boolean[maxGridRows][maxGridCols];
        
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

        OnvifDiscoveryService.discoverDevices(dispositivos -> {
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

                    for (String lambdaLinha : linhasSelecionadas) {
                        String ip = lambdaLinha.split(" ")[0].trim();
                        String modelo = dispositivos.get(ip);

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

                            String serviceUrl = dispositivos.get(ip); 

                            String rtspUrl = serviceOnvif.obterUrlRtsp(serviceUrl, user, pass, modelo, ip, isSubstream);

                            if (rtspUrl != null) {
                                // Criando a nova câmera utilizando os spans informados
                                Camera novaCam = new Camera(nomeFinal, rtspUrl, rSpan, cSpan);
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