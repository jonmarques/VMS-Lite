package br.com.jonmarques.vmslite.ui;

import com.formdev.flatlaf.FlatDarculaLaf;
import java.awt.Component;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JButton;
import javax.swing.JPasswordField;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;

public final class ProtectedUrlChecks {
    public static void main(String[] args) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            FlatDarculaLaf.setup();
            AtomicBoolean allowed = new AtomicBoolean(false);
            AtomicInteger requests = new AtomicInteger();
            String url = "rtsp://admin:secret@camera.local/sub";
            ProtectedUrlField control = new ProtectedUrlField(url, () -> {
                requests.incrementAndGet();
                return allowed.get();
            });
            JPasswordField field = (JPasswordField) control.getComponent(0);
            JButton button = (JButton) control.getComponent(1);
            require(field.getEchoChar() != 0, "URL starts masked");
            require(!field.getDragEnabled(), "Secret cannot be dragged to another field");
            require(Boolean.FALSE.equals(field.getClientProperty("JPasswordField.cutCopyAllowed")),
                    "Copy and cut are disabled");
            for (Component child : field.getComponents()) {
                require(!(child instanceof JToggleButton), "No built-in unauthenticated reveal button");
            }
            button.doClick(0);
            require(field.getEchoChar() != 0, "Denied or cancelled login keeps URL hidden");
            require(url.equals(control.readUrl()), "Masking must preserve the saved URL");
            allowed.set(true);
            button.doClick(0);
            require(field.getEchoChar() == 0, "Successful login reveals URL");
            button.doClick(0);
            require(field.getEchoChar() != 0, "Second click hides URL");
            require(requests.get() == 2, "Hiding does not request authentication");
            allowed.set(false);
            button.doClick(0);
            require(requests.get() == 3 && field.getEchoChar() != 0, "Revealing again requires new authentication");
            control.clear();
            require(control.readUrl().isEmpty() && field.getEchoChar() != 0, "Closing clears and masks the field");
            System.out.println("OK: masked URL, authenticated reveal, cancellation, hide, repeat authentication, cleanup");
        });
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
