package br.com.jonmarques.vmslite;

import java.nio.file.*;
import java.util.Optional;

/** Resolve the packaged application independently of its working directory. */
public final class ApplicationPaths {
    private ApplicationPaths() {}

    public static Optional<Path> executable() {
        return findExecutable(codeDirectory(), ProcessHandle.current().info().command().orElse(null));
    }

    static Optional<Path> findExecutable(Path directory, String runningCommand) {
        if (runningCommand != null) {
            Path running = Path.of(runningCommand).toAbsolutePath().normalize();
            String name = running.getFileName().toString();
            if ((name.equalsIgnoreCase("VMS Lite.exe") || name.equalsIgnoreCase("VMSLite.exe"))
                    && Files.isRegularFile(running)) return Optional.of(running);
        }
        for (String name : new String[]{"VMS Lite.exe", "VMSLite.exe"}) {
            Path candidate = directory.resolve(name).toAbsolutePath().normalize();
            if (Files.isRegularFile(candidate)) return Optional.of(candidate);
        }
        return Optional.empty();
    }

    public static Path installDirectory() {
        return executable().map(Path::getParent).orElseGet(ApplicationPaths::codeDirectory);
    }

    public static Path dataDirectory() { return Path.of(System.getProperty("user.home"), ".vmslite"); }

    private static Path codeDirectory() {
        try {
            Path location = Path.of(ApplicationPaths.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (Files.isRegularFile(location)) {
                Path parent = location.getParent();
                return parent.getFileName().toString().equalsIgnoreCase("app") ? parent.getParent() : parent;
            }
        } catch (Exception ignored) { }
        return Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
    }
}
