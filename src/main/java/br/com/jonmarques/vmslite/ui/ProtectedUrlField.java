package br.com.jonmarques.vmslite.ui;

import com.formdev.flatlaf.icons.FlatRevealIcon;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.util.Arrays;
import java.util.function.BooleanSupplier;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JPasswordField;

public final class ProtectedUrlField extends JPanel {
    private static final long serialVersionUID = 1L;
    private final JPasswordField field;
    private final JButton reveal;
    private final char echoChar;

    public ProtectedUrlField(String url, BooleanSupplier authorize) {
        super(new BorderLayout(5, 0));
        field = new JPasswordField(url, 35);
        field.putClientProperty("FlatLaf.style", "showRevealButton: false");
        field.putClientProperty("JPasswordField.cutCopyAllowed", false);
        field.setDragEnabled(false);
        echoChar = field.getEchoChar() == 0 ? '\u2022' : field.getEchoChar();
        field.setEchoChar(echoChar);
        reveal = new JButton(new FlatRevealIcon());
        reveal.setPreferredSize(new Dimension(36, 30));
        reveal.setToolTipText("Revelar URL (administrador)");
        reveal.getAccessibleContext().setAccessibleName("Revelar URL");
        reveal.addActionListener(event -> {
            if (field.getEchoChar() == 0) {
                hideUrl();
            } else if (authorize.getAsBoolean()) {
                field.setEchoChar((char) 0);
                reveal.setToolTipText("Ocultar URL");
                reveal.getAccessibleContext().setAccessibleName("Ocultar URL");
            }
        });
        add(field, BorderLayout.CENTER);
        add(reveal, BorderLayout.EAST);
    }

    public String readUrl() {
        char[] value = field.getPassword();
        try { return new String(value).trim(); }
        finally { Arrays.fill(value, '\0'); }
    }

    public void hideUrl() {
        field.setEchoChar(echoChar);
        reveal.setToolTipText("Revelar URL (administrador)");
        reveal.getAccessibleContext().setAccessibleName("Revelar URL");
    }

    public void clear() {
        hideUrl();
        field.setText("");
    }
}
