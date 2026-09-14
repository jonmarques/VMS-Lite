package br.com.jonmarques.vmslite.ui;
import br.com.jonmarques.vmslite.*;
import br.com.jonmarques.vmslite.entity.Camera;
import br.com.jonmarques.vmslite.service.*;
import java.awt.*;
import java.util.*;
import java.util.List;
import javax.swing.*;
public final class OnvifDiscoveryDialog {
    private OnvifDiscoveryDialog() {}
    public static void show(VMSLite owner, JButton botaoMenu) {
        int generation = owner.getConfigurationGeneration();
        botaoMenu.setEnabled(false);
        botaoMenu.setText("Escaneando Rede...");

        JDialog loadingDialog = new JDialog(owner, "Aguarde", Dialog.ModalityType.MODELESS);
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
        loadingDialog.setLocationRelativeTo(owner);
        loadingDialog.setVisible(true);

        OnvifDiscoveryService.discoverDevices(true, dispositivos -> {
            SwingUtilities.invokeLater(() -> {

                loadingDialog.dispose();
                if (!owner.acceptsCameraResults(generation)) return;

                botaoMenu.setEnabled(true);
                botaoMenu.setText("Buscar ONVIF 🔍");
                Set<String> ipsConectados = new HashSet<>();
                for (Camera camera : owner.getCameraConfigs()) {
                    String host = CameraAddress.host(camera.getUrl());
                    if (host != null) ipsConectados.add(host);
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
                    return ip1.compareTo(ip2);
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
                    show(owner, botaoMenu);
                });

                if (ipsFiltrados.isEmpty()) {
                    listModel.addElement("Nenhum dispositivo novo encontrado.");
                    deviceList.setEnabled(false);
                }

                int option = JOptionPane.showConfirmDialog(owner, painelJanela,
                        "Câmeras ONVIF Descobertas", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);

                if (option == JOptionPane.OK_OPTION && deviceList.isEnabled()) {
                    int[] selectedIndices = deviceList.getSelectedIndices();
                    if (selectedIndices.length == 0) return;

                    OnvifMediaService serviceOnvif = new OnvifMediaService();

                    for (int selectedIndex : selectedIndices) {
                        String ip = ipsFiltrados.get(selectedIndex);
                        String uuid = dispositivos.get(ip).getUuid();
                        String modelo = dispositivos.get(ip).getXaddr();

                        JTextField userField = new JTextField("admin");
                        JTextField nameField = new JTextField(modelo + " (" + ip + ")");

                        // Novos campos para a dimensão do Grid (com valor padrão "1")
                        JSpinner linhasField = new JSpinner(new SpinnerNumberModel(1, 1, 20, 1));
                        JSpinner colunasField = new JSpinner(new SpinnerNumberModel(1, 1, 20, 1));

                        String[] streams = {"Mainstream (Alta Resolução)", "Substream (Leve/Fluido)"};
                        JComboBox<String> streamCombo = new JComboBox<>(streams);
                        streamCombo.setSelectedIndex(1);

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

                        int loginOption = JOptionPane.showConfirmDialog(owner, loginFields,
                                "Autenticar: " + ip, JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);

                        if (loginOption == JOptionPane.OK_OPTION) {
                            String user = userField.getText().trim();
                            String pass = new String(passField.getPassword());
                            boolean isSubstream = streamCombo.getSelectedIndex() == 1;
                            String nomeFinal = nameField.getText().trim();

                            int rowSpan = (Integer) linhasField.getValue();
                            int colSpan = (Integer) colunasField.getValue();
                            String serviceUrl = dispositivos.get(ip).getXaddr();
                            new SwingWorker<String, Void>() {
                                @Override protected String doInBackground() {
                                    return serviceOnvif.obterUrlRtsp(serviceUrl, user, pass, modelo, ip, isSubstream);
                                }
                                @Override protected void done() {
                                    if (!owner.acceptsCameraResults(generation)) return;
                                    try {
                                        String rtspUrl = get();
                                        if (rtspUrl == null) throw new IllegalStateException("URL RTSP indisponivel");
                                        owner.addCamera(new Camera(nomeFinal, rtspUrl, uuid, rowSpan, colSpan));
                                    } catch (Exception ex) {
                                        JOptionPane.showMessageDialog(owner, "Nao foi possivel conectar a camera " + ip,
                                                "Erro de conexao", JOptionPane.ERROR_MESSAGE);
                                    }
                                }
                            }.execute();
                        }
                    }

                }
            });
        });
    }

}
