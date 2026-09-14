package br.com.jonmarques.vmslite;
import com.sun.jna.NativeLibrary;
import uk.co.caprica.vlcj.binding.support.runtime.RuntimeUtil;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ApplicationBootstrap {
    private ApplicationBootstrap() {}
    public static void prepare() {
        // Keep AWT painting defaults when mixing Swing with VLC's native Canvas.

        String basePath = System.getProperty("user.dir");

        NativeLibrary.addSearchPath(
                RuntimeUtil.getLibVlcLibraryName(),
                basePath + "/vlc"
                );

        NativeLibrary.addSearchPath(
                RuntimeUtil.getLibVlcLibraryName(),
                basePath + "/app/vlc"
                );

    }
    public static void addToStartup(String appName, String exePath) {
        if (!Files.isRegularFile(Path.of(exePath))) return;
        try {
            ProcessBuilder check = new ProcessBuilder(
                    "reg",
                    "query",
                    "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run",
                    "/v",
                    appName
                    );

            check.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            check.redirectError(ProcessBuilder.Redirect.DISCARD);
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
                    "/d", "\"" + exePath + "\"",
                    "/f"
                    );

            add.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            add.redirectError(ProcessBuilder.Redirect.DISCARD);
            if (add.start().waitFor() != 0) return;
            logDebug("Adicionado ao Startup!");

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void logDebug(String message) {
        if (Boolean.getBoolean("vmslite.debug")) System.out.println(message);
    }
}
