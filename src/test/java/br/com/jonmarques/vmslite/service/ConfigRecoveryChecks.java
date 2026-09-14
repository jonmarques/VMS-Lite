package br.com.jonmarques.vmslite.service;

import br.com.jonmarques.vmslite.entity.VMSConfig;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicReference;

public final class ConfigRecoveryChecks {
    public static void main(String[] args) throws Exception {
        Path file = Files.createTempFile(Path.of("target"), "recovery-", ".json");
        AtomicReference<Exception> failure = new AtomicReference<>();
        try {
            Files.writeString(file, "{broken-original");
            try (ConfigWriter writer = new ConfigWriter(failure::set, config -> ConfigService.saveToFile(config, file.toFile()))) {
                try { ConfigService.loadFromFile(file.toFile()); throw new AssertionError("Invalid file accepted"); }
                catch (java.io.UncheckedIOException expected) { }
                writer.save(new VMSConfig());
            }
            if (!Files.readString(file).equals("{broken-original")) throw new AssertionError("Recovery overwrote original");
            try (ConfigWriter writer = new ConfigWriter(failure::set, config -> ConfigService.saveToFile(config, file.toFile()))) {
                writer.enableWrites();
                writer.save(new VMSConfig());
            }
            if (failure.get() != null) throw new AssertionError(failure.get());
            ConfigService.loadFromFile(file.toFile());
            System.out.println("OK: blocked writes preserve corrupt configuration; explicit restore enables persistence");
        } finally { Files.deleteIfExists(file); }
    }
}
