package br.com.jonmarques.vmslite;
import javax.swing.*;
import br.com.jonmarques.vmslite.ui.*;

import com.formdev.flatlaf.FlatDarculaLaf;
import uk.co.caprica.vlcj.player.embedded.fullscreen.FullScreenStrategy;
import uk.co.caprica.vlcj.player.embedded.fullscreen.windows.Win32FullScreenStrategy;

import br.com.jonmarques.vmslite.entity.Camera;
import br.com.jonmarques.vmslite.entity.VMSConfig;
import br.com.jonmarques.vmslite.service.ConfigService;
import br.com.jonmarques.vmslite.service.AdministratorService;
import br.com.jonmarques.vmslite.service.OnvifDiscoveryService;

import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;

public class VMSLite extends JFrame {

    private static final long serialVersionUID = 1L;
    private static final boolean DEBUG = Boolean.getBoolean("vmslite.debug");

    private static VMSLite instance;

    private JPanel camerasPanel;

    private final List<CameraPanel> cameras = new ArrayList<>();

    private VMSConfig vmsconfig = new VMSConfig();
    private boolean closing;
    private int configurationGeneration;
    private boolean loadingConfiguration = true;
    private Timer sequentialOpener;
    private CameraTourPanel tourPanel;
    private final br.com.jonmarques.vmslite.service.ConfigWriter configWriter =
            new br.com.jonmarques.vmslite.service.ConfigWriter(error -> SwingUtilities.invokeLater(() ->
                    JOptionPane.showMessageDialog(this, "Erro ao salvar configuracao: " + error.getMessage(),
                            "Erro", JOptionPane.ERROR_MESSAGE)));

    private boolean fullscreen = false;
    private final FullScreenStrategy fullscreenStrategy = new Win32FullScreenStrategy(this);

    private JPanel topBar;
    private JButton btnFullscreen;
    private JButton importButton;
    private boolean configurationRecoveryRequired;
    private FullscreenControls fullscreenControls;
    private CameraMetricsOverlay metricsOverlay;

    private Timer resizeDebounce;

    // Gerenciadores do carregamento assíncrono
    private JPanel mainContainer;
    private CardLayout cardLayout;
    private JLabel lblStatus;

    public VMSLite() {

        super("VMS Lite");

        setSize(1400, 900);
        setLocationRelativeTo(null);

        // 1. Cria a interface estrutural com a tela de "Carregando..." ativa
        createInterface();
        setConfigurationActionsEnabled(false);

        // 2. Torna a janela visível imediatamente para exibir o feedback visual
        setVisible(true);

        instance = this;

        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosing(java.awt.event.WindowEvent event) { closeApplication(); }
        });
        new SwingWorker<VMSConfig, Void>() {
            @Override protected VMSConfig doInBackground() {
                try {
                    AdministratorService.getDefault().isConfigured();
                } catch (IllegalStateException error) {
                    SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(VMSLite.this,
                            "Nao foi possivel atualizar o cadastro de administrador. A revelacao de URLs permanece bloqueada.",
                            "Administrador", JOptionPane.ERROR_MESSAGE));
                }
                VlcManager.init();
                return ConfigService.load();
            }
            @Override protected void done() {
                if (closing) return;
                try {
                    vmsconfig = get();
                    configWriter.enableWrites();
                    loadSavedCameras();
                } catch (Exception error) {
                    configurationRecoveryRequired = !configWriter.isWritable();
                    JOptionPane.showMessageDialog(VMSLite.this,
                            "Erro ao carregar configuracao. O arquivo original foi preservado.\n"
                            + "Use Importar para restaurar uma configuracao valida antes de fazer alteracoes.",
                            "Erro", JOptionPane.ERROR_MESSAGE);
                    cardLayout.show(mainContainer, "CAMERAS");
                    setConfigurationActionsEnabled(true);
                }
            }
        }.execute();

        toggleFullscreen();

        AppWatchdog.start();
        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() {
                ExternalWatchdogInstaller.install();
                return null;
            }
        }.execute();
    }

    public boolean isFullscreen() {
        return this.fullscreen;
    }

    public void reordenarCameras(CameraPanel origem, CameraPanel destino) {
        int indexOrigem = cameras.indexOf(origem);
        int indexDestino = cameras.indexOf(destino);

        if (indexOrigem != -1 && indexDestino != -1) {
            Collections.swap(cameras, indexOrigem, indexDestino);
            Collections.swap(vmsconfig.getCameras(), vmsconfig.getCameras().indexOf(origem.getConfig()),
                    vmsconfig.getCameras().indexOf(destino.getConfig()));
            saveConfigs();
        }
        rebuildLayout();
    }

    private void createInterface() {
        FlatDarculaLaf.setup();

        cardLayout = new CardLayout();
        mainContainer = new JPanel(cardLayout);
        mainContainer.setOpaque(true);
        mainContainer.setBackground(Color.BLACK);
        getContentPane().setBackground(Color.BLACK);

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
        camerasPanel.setOpaque(true);
        camerasPanel.setBackground(new Color(30, 30, 30));

        mainContainer.add(loadingScreen, "LOADING");
        mainContainer.add(camerasPanel, "CAMERAS");

        // Adiciona o container gerenciador no centro do Frame
        add(mainContainer, BorderLayout.CENTER);
        cardLayout.show(mainContainer, "LOADING");
        this.resizeDebounce = new Timer(80, ev -> rebuildLayout());
        this.resizeDebounce.setRepeats(false);
        camerasPanel.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                resizeDebounce.restart(); // restart() cancela o timer anterior se estivesse rodando e inicia do zero
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

        importButton = new JButton("Importar");
        JButton tourButton = new JButton("Tour");
        tourButton.addActionListener(e -> {
            var settings = CameraTourDialog.show(this, vmsconfig.getCameras(), vmsconfig.getCameraTour());
            if (settings != null) {
                vmsconfig.setCameraTour(settings);
                synchronizeTour();
                openMissingCameras();
                saveConfigs();
            }
        });
        topBar.add(tourButton);
        topBar.add(Box.createHorizontalStrut(5));
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
        btnFullscreen.putClientProperty("FlatLaf.style",
                "background: #1e90ff; foreground: #ffffff; hoverBackground: #1874cc; "
                + "hoverForeground: #ffffff; pressedBackground: #145fa6; pressedForeground: #ffffff");
        btnFullscreen.setFont(btnFullscreen.getFont().deriveFont(Font.BOLD));

        btnFullscreen.addActionListener(e -> toggleFullscreen());
        topBar.add(btnFullscreen);

        add(topBar, BorderLayout.NORTH);

        fullscreenControls = new FullscreenControls(this, this::toggleFullscreen);
        metricsOverlay = new CameraMetricsOverlay(this, () -> cameras);
    }

    public void gerenciarBotaoJanela(Point pontoNaTela) {
        if (fullscreen) fullscreenControls.update(pontoNaTela);
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
        cameras.sort(java.util.Comparator.comparingInt(panelItem ->
                vmsconfig.getCameras().indexOf(panelItem.getConfig())));
        camerasPanel.add(panel);

        if (!deferLayout) {
            rebuildLayout();
        }
        return panel;
    }

    private boolean aplicarAtualizacoesOnvif(List<Camera> cameraList, Map<String, OnvifDiscoveryService.DeviceInfo> dispositivos) {
        boolean necessarioSalvar = false;
        Set<Camera> urlAlteradas = new HashSet<>();

        for (Camera config : cameraList) {
            if (!vmsconfig.getCameras().contains(config)) continue;
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
        cardLayout.show(mainContainer, "CAMERAS");
        setConfigurationActionsEnabled(true);
        synchronizeTour();
        openMissingCameras();
        OnvifDiscoveryService.discoverDevices(devices -> SwingUtilities.invokeLater(() -> {
            if (!closing && aplicarAtualizacoesOnvif(saved, devices)) saveConfigs();
        }));
    }

    private void synchronizeTour() {
        if (vmsconfig.getCameraTour().isEnabled()) {
            if (tourPanel == null) {
                tourPanel = new CameraTourPanel(this);
                camerasPanel.add(tourPanel);
            }
            tourPanel.configure(vmsconfig.getCameraTour(), vmsconfig.getCameras());
        } else if (tourPanel != null) {
            tourPanel.stop();
            camerasPanel.remove(tourPanel);
            tourPanel = null;
        }
        rebuildLayout();
    }

    private void openMissingCameras() {
        if (sequentialOpener != null) sequentialOpener.stop();
        List<Camera> saved = vmsconfig.getCameras().stream().filter(camera ->
                cameras.stream().noneMatch(panel -> panel.getConfig() == camera)).toList();
        final int[] index = {0};
        sequentialOpener = new Timer(250, event -> {
            if (closing || index[0] >= saved.size()) {
                ((Timer) event.getSource()).stop();
                return;
            }
            Camera camera = saved.get(index[0]++);
            if (vmsconfig.getCameras().contains(camera)
                    && cameras.stream().noneMatch(panel -> panel.getConfig() == camera)) {
                addCameraPanel(camera, false).start();
            }
        });
        sequentialOpener.start();
    }

    private void setConfigurationActionsEnabled(boolean enabled) {
        loadingConfiguration = !enabled;
        for (Component component : topBar.getComponents()) {
            if (component instanceof JButton) component.setEnabled(enabled && (!configurationRecoveryRequired
                    || component == importButton || component == btnFullscreen));
        }
    }

    public int getConfigurationGeneration() { return configurationGeneration; }
    public boolean acceptsCameraResults(int generation) {
        return !closing && !loadingConfiguration && generation == configurationGeneration;
    }

    public void saveConfigs() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::saveConfigs);
            return;
        }
        if (!closing && configWriter.isWritable()) {
            if (tourPanel != null) tourPanel.configure(vmsconfig.getCameraTour(), vmsconfig.getCameras());
            configWriter.save(ConfigService.snapshot(vmsconfig));
        }
    }

    private void closeApplication() {
        if (closing) return;
        closing = true;
        fullscreenControls.dispose();
        metricsOverlay.dispose();
        resizeDebounce.stop();
        if (sequentialOpener != null) sequentialOpener.stop();
        AppWatchdog.stop();
        List<Future<?>> releases = new ArrayList<>();
        if (tourPanel != null) releases.add(tourPanel.stop());
        for (CameraPanel panel : cameras) {
            releases.add(panel.stop());
        }
        new Thread(() -> {
            configWriter.close();
            boolean released = CameraPanel.shutdownExecutors();
            if (released && releases.stream().allMatch(Future::isDone)) VlcManager.shutdown();
            SwingUtilities.invokeLater(() -> {
                dispose();
                System.exit(0);
            });
        }, "VMSLite-shutdown").start();
    }

    public void removeCamera(CameraPanel panel) {
        removeCamera(panel.getConfig());
    }

    public void removeCamera(Camera camera) {
        for (CameraPanel panel : new ArrayList<>(cameras)) {
            if (panel.getConfig() == camera) {
                panel.stop();
                cameras.remove(panel);
                camerasPanel.remove(panel);
            }
        }
        vmsconfig.getCameras().remove(camera);
        vmsconfig.getCameraTour().getCameraIds().remove(camera.getId());
        if (vmsconfig.getCameraTour().getCameraIds().isEmpty()) vmsconfig.getCameraTour().setEnabled(false);
        synchronizeTour();
        saveConfigs();

        camerasPanel.revalidate();
        camerasPanel.repaint();
    }

    private void importarConfig() {
        JFileChooser chooser = new JFileChooser();
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File file = chooser.getSelectedFile();
        configurationGeneration++;
        setConfigurationActionsEnabled(false);
        cardLayout.show(mainContainer, "LOADING");
        lblStatus.setText("Importando configuracao...");
        new SwingWorker<VMSConfig, Void>() {
            @Override protected VMSConfig doInBackground() { return ConfigService.loadFromFile(file); }
            @Override protected void done() {
                if (closing) return;
                try {
                    VMSConfig imported = get();
                    configurationRecoveryRequired = false;
                    configWriter.enableWrites();
                    if (sequentialOpener != null) sequentialOpener.stop();
                    cameras.forEach(CameraPanel::stop);
                    if (tourPanel != null) { tourPanel.stop(); tourPanel = null; }
                    cameras.clear();
                    camerasPanel.removeAll();
                    vmsconfig = imported;
                    loadSavedCameras();
                    saveConfigs();
                } catch (Exception error) {
                    JOptionPane.showMessageDialog(VMSLite.this, "Configuracao invalida. As cameras atuais foram mantidas.",
                            "Erro ao importar", JOptionPane.ERROR_MESSAGE);
                } finally {
                    setConfigurationActionsEnabled(true);
                    cardLayout.show(mainContainer, "CAMERAS");
                    rebuildLayout();
                }
            }
        }.execute();
    }

    private void exportarConfig() {
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new File("vms-backup.json"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File file = chooser.getSelectedFile();
        VMSConfig snapshot = ConfigService.snapshot(vmsconfig);
        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() {
                ConfigService.saveToFile(snapshot, file);
                return null;
            }
            @Override protected void done() {
                try {
                    get();
                    JOptionPane.showMessageDialog(VMSLite.this, "Exportado com sucesso!");
                } catch (Exception error) {
                    JOptionPane.showMessageDialog(VMSLite.this, "Erro ao exportar configuracao.",
                            "Erro", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    private void toggleFullscreen() {
        fullscreenControls.setEnabled(false);
        if (!fullscreen) {
            fullscreenStrategy.enterFullScreenMode();
            topBar.setVisible(false);
            fullscreen = true;
        } else {
            fullscreenStrategy.exitFullScreenMode();
            topBar.setVisible(true);
            fullscreen = false;
        }

        fullscreenControls.setEnabled(fullscreen);
        revalidate();
        rebuildLayout();
        repaint();

    }
    public void rebuildLayout() {
        if (vmsconfig != null) {
            CameraPanel active = tourPanel == null ? null : cameras.stream()
                    .filter(panel -> panel.getConfig() == tourPanel.getCurrent()).findFirst().orElse(null);
            CameraGridLayout.apply(camerasPanel, cameras, tourPanel, active,
                    vmsconfig.getLayoutRows(), vmsconfig.getLayoutCols());
            if (tourPanel != null) tourPanel.setTarget(active);
        }
    }

    public List<Camera> getCameraConfigs() { return List.copyOf(vmsconfig.getCameras()); }

    public void addCamera(Camera camera) {
        if (closing) return;
        addCameraPanel(camera, false).start();
        saveConfigs();
    }

    public void showEditCameraDialog(CameraPanel panel) { showEditCameraDialog(panel.getConfig()); }
    public void showEditCameraDialog(Camera camera) {
        CameraTourPanel controls = tourPanel;
        boolean paused = controls != null && controls.isPaused();
        if (controls != null) controls.setPaused(true);
        try { CameraEditorDialog.show(this, camera); }
        finally { if (controls != null) controls.setPaused(paused); }
    }
    public void restartCamera(Camera camera) {
        cameras.stream().filter(panel -> panel.getConfig() == camera).forEach(CameraPanel::start);
    }

    private void executarBuscaOnvif(JButton button) { OnvifDiscoveryDialog.show(this, button); }

    public static void addToStartup(String appName, String exePath) { ApplicationBootstrap.addToStartup(appName, exePath); }

    private static void logDebug(String message) {
        if (DEBUG) {
            System.out.println(message);
        }
    }

    public static void main(String[] args) {
        ApplicationBootstrap.prepare();
        // Executa validações de instância única e registros no SO antes de criar a UI
        if (!SingleInstance.lock()) {
            System.exit(0);
        }
        Runtime.getRuntime().addShutdownHook(new Thread(SingleInstance::unlock));

        ApplicationPaths.executable().ifPresent(exe -> addToStartup("VMSLite", exe.toString()));

        SwingUtilities.invokeLater(VMSLite::new);
    }

    public static VMSLite getInstance() {
        return instance;
    }
}
