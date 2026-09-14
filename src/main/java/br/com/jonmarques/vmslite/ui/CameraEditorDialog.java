package br.com.jonmarques.vmslite.ui;

import br.com.jonmarques.vmslite.*;
import br.com.jonmarques.vmslite.entity.Camera;
import br.com.jonmarques.vmslite.service.*;

import javax.swing.*;

public final class CameraEditorDialog {
    private CameraEditorDialog() {}
    public static void show(VMSLite owner, CameraPanel panel) {
        show(owner, panel.getConfig());
    }

    public static void show(VMSLite owner, Camera config) {

        JTextField nameField = new JTextField(config.getName());
        ProtectedUrlField urlField = new ProtectedUrlField(config.getUrl(),
                () -> AdministratorDialog.authenticate(owner));
        JTextField linhasField = new JTextField(String.valueOf(config.getRowSpan()));
        JTextField colunasField = new JTextField(String.valueOf(config.getColSpan()));

        Object[] fields = {
                "Nome:", nameField,
                "RTSP URL:", urlField,
                "Linhas (Row Span):", linhasField,
                "Colunas (Col Span):", colunasField
        };

        Object[] options = {"Salvar", "Excluir Câmera", "Cancelar"};

        int result;
        String editedUrl;
        try {
            result = JOptionPane.showOptionDialog(
                owner, fields, "Editar Câmera: " + config.getName(),
                JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE, null, options, options[0]
                );
            editedUrl = result == 0 ? urlField.readUrl() : null;
        } finally {
            urlField.clear();
        }

        if (result == 0) {

                try {
                    String oldUrl = config.getUrl();
                    String newUrl = editedUrl;
                    int rowSpan = ConfigService.dimension(Integer.parseInt(linhasField.getText().trim()));
                    int colSpan = ConfigService.dimension(Integer.parseInt(colunasField.getText().trim()));
                    if (CameraAddress.host(newUrl) == null) throw new IllegalArgumentException("URL invalida");
                    config.setName(nameField.getText());
                    config.setUrl(newUrl);
                    config.setRowSpan(rowSpan);
                    config.setColSpan(colSpan);

                    owner.saveConfigs();

                    if (!newUrl.equals(oldUrl)) {

                        owner.restartCamera(config);
                    }

                    owner.rebuildLayout();

                    String newIp = OnvifDiscoveryService.extrairIpDaUrl(newUrl);
                    String oldIp = OnvifDiscoveryService.extrairIpDaUrl(oldUrl);
                    if (newIp != null && oldIp != null && !newIp.equals(oldIp)) {

                        config.setUuid(null);
                        owner.saveConfigs();
                    }

                } catch (IllegalArgumentException ex) {
                    JOptionPane.showMessageDialog(owner,
                            "Informe uma URL valida e dimensoes entre 1 e 20.",
                            "Erro de Validação", JOptionPane.ERROR_MESSAGE);
                }
        } else if (result == 1) {
            int confirmar = JOptionPane.showConfirmDialog(
                    owner, "Tem certeza que deseja remover a câmera \"" + config.getName() + "\"?",
                    "Confirmar Exclusão", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE
                    );

            if (confirmar == JOptionPane.YES_OPTION) {
                owner.removeCamera(config);
                owner.rebuildLayout();
            }
        }
    }

}
