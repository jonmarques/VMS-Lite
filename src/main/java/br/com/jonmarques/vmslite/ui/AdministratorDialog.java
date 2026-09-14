package br.com.jonmarques.vmslite.ui;

import br.com.jonmarques.vmslite.service.AdministratorService;
import java.awt.*;
import java.util.Arrays;
import java.util.concurrent.ExecutionException;
import javax.swing.*;

public final class AdministratorDialog {
    private AdministratorDialog() {}

    public static boolean authenticate(Component parent) {
        AdministratorService service = AdministratorService.getDefault();
        Window owner = parent instanceof Window ? (Window) parent : SwingUtilities.getWindowAncestor(parent);
        JDialog dialog = new JDialog(owner,
                "Autenticacao de administrador", Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        JPanel content = new JPanel(new BorderLayout(8, 12));
        content.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        JLabel message = new JLabel("Verificando cadastro...");
        message.setPreferredSize(new Dimension(480, 45));
        content.add(message, BorderLayout.NORTH);
        JTextField login = new JTextField(24);
        JPasswordField password = new JPasswordField(24);
        JPasswordField confirmation = new JPasswordField(24);
        password.putClientProperty("FlatLaf.style", "showRevealButton: false");
        confirmation.putClientProperty("FlatLaf.style", "showRevealButton: false");
        JPanel fields = new JPanel(new GridLayout(0, 1, 0, 5));
        fields.add(new JLabel("Login:"));
        fields.add(login);
        fields.add(new JLabel("Senha:"));
        fields.add(password);
        JLabel confirmationLabel = new JLabel("Confirmar senha:");
        fields.add(confirmationLabel);
        fields.add(confirmation);
        content.add(fields, BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton submit = new JButton("Entrar");
        JButton cancel = new JButton("Cancelar");
        actions.add(submit);
        actions.add(cancel);
        content.add(actions, BorderLayout.SOUTH);
        submit.setEnabled(false);
        confirmationLabel.setVisible(false);
        confirmation.setVisible(false);
        boolean[] create = {false};
        boolean[] authenticated = {false};
        cancel.addActionListener(event -> dialog.dispose());
        dialog.setContentPane(content);
        dialog.getRootPane().setDefaultButton(submit);

        new SwingWorker<Boolean, Void>() {
            @Override protected Boolean doInBackground() { return service.isConfigured(); }
            @Override protected void done() {
                if (!dialog.isDisplayable()) return;
                try {
                    create[0] = !get();
                    message.setText(create[0]
                            ? "<html>Login e Senha de Administrador não setados. Crie agora um:</html>"
                            : "Informe o login e a senha de administrador.");
                    submit.setText(create[0] ? "Criar administrador" : "Entrar");
                    confirmationLabel.setVisible(create[0]);
                    confirmation.setVisible(create[0]);
                    if (create[0]) password.setToolTipText("Minimo de 12 caracteres.");
                    submit.setEnabled(true);
                    dialog.pack();
                    dialog.setLocationRelativeTo(parent);
                    login.requestFocusInWindow();
                } catch (Exception error) {
                    message.setText("<html>Cadastro de administrador indisponivel. A visualizacao foi bloqueada.</html>");
                }
            }
        }.execute();

        submit.addActionListener(event -> {
            char[] secret = password.getPassword();
            char[] repeated = confirmation.getPassword();
            String username = login.getText();
            if (create[0] && !Arrays.equals(secret, repeated)) {
                Arrays.fill(secret, '\0');
                Arrays.fill(repeated, '\0');
                message.setText("As senhas nao coincidem.");
                return;
            }
            Arrays.fill(repeated, '\0');
            password.setText("");
            confirmation.setText("");
            submit.setEnabled(false);
            login.setEnabled(false);
            password.setEnabled(false);
            confirmation.setEnabled(false);
            // Once registration starts, wait for the result so its completion cannot go unnoticed.
            cancel.setEnabled(false);
            dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
            message.setText(create[0] ? "Criando administrador..." : "Validando...");
            new SwingWorker<Boolean, Void>() {
                @Override protected Boolean doInBackground() {
                    try {
                        if (create[0]) {
                            service.create(username, secret);
                            return true;
                        }
                        return service.authenticate(username, secret);
                    } finally {
                        Arrays.fill(secret, '\0');
                    }
                }
                @Override protected void done() {
                    try {
                        if (get()) {
                            authenticated[0] = true;
                            dialog.dispose();
                        } else {
                            message.setText("Login ou senha incorretos.");
                        }
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        message.setText("Validacao interrompida.");
                    } catch (ExecutionException error) {
                        Throwable cause = error.getCause();
                        String text = cause instanceof IllegalArgumentException || cause instanceof IllegalStateException
                                ? cause.getMessage() : "Nao foi possivel validar o administrador.";
                        message.setText("<html>" + text + "</html>");
                    } finally {
                        login.setEnabled(true);
                        password.setEnabled(true);
                        confirmation.setEnabled(true);
                        submit.setEnabled(true);
                        cancel.setEnabled(true);
                        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
                        password.requestFocusInWindow();
                    }
                }
            }.execute();
        });
        dialog.pack();
        dialog.setResizable(false);
        dialog.setLocationRelativeTo(parent);
        try {
            dialog.setVisible(true);
            return authenticated[0];
        } finally {
            password.setText("");
            confirmation.setText("");
            dialog.dispose();
        }
    }
}
