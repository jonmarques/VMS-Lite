package br.com.jonmarques.vmslite;

import java.nio.file.*;

public final class ApplicationPathsChecks {
    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory(Path.of("target"), "app-paths-").toAbsolutePath();
        Path normal = directory.resolve("VMS Lite.exe"), legacy = directory.resolve("VMSLite.exe");
        try {
            if (ApplicationPaths.findExecutable(directory, null).isPresent()) throw new AssertionError("Missing executable");
            Files.writeString(legacy, "test fixture; not executable");
            if (!ApplicationPaths.findExecutable(directory, "javaw.exe").orElseThrow().equals(legacy)) throw new AssertionError("Legacy fallback");
            Files.writeString(normal, "test fixture; not executable");
            if (!ApplicationPaths.findExecutable(directory, null).orElseThrow().equals(normal)) throw new AssertionError("Packaged name");
            if (!ApplicationPaths.findExecutable(directory.resolve("wrong cwd"), normal.toString()).orElseThrow().equals(normal))
                throw new AssertionError("Running executable independent of working directory");
            System.out.println("OK: packaged/legacy names and launch from another working directory");
        } finally { Files.deleteIfExists(normal); Files.deleteIfExists(legacy); Files.deleteIfExists(directory); }
    }
}
