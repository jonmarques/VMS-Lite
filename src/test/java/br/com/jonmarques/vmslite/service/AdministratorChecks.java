package br.com.jonmarques.vmslite.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class AdministratorChecks {
    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory(Path.of("target"), "admin-check-");
        Path firstFile = directory.resolve("first.json");
        Path secondFile = directory.resolve("second.json");
        Path copiedFile = directory.resolve("copied.json");
        char[] password = "long-test-pass!37".toCharArray();
        char[] wrongPassword = "different-test-pass".toCharArray();
        try {
            AdministratorService first = new AdministratorService(firstFile);
            require(!first.isConfigured(), "Missing administrator should allow initial registration");
            reject(() -> first.create("", password));
            reject(() -> first.create("admin", new char[2]));
            require(!Files.exists(firstFile), "Invalid registration must not create a file");

            first.create("admin", password);
            require(first.isConfigured(), "Administrator must be persisted");
            require(first.authenticate("admin", password), "Correct credentials");
            require(!first.authenticate("admin", wrongPassword), "Wrong password must fail");
            require(!first.authenticate("other", password), "Wrong login must fail");
            require(first.authenticate("admin", password), "Valid login clears failed attempts");
            String stored = Files.readString(firstFile);
            require(!stored.contains("\"admin\"") && !stored.contains("\"login\":"),
                    "Login must not be stored in clear text");
            require(!stored.contains(new String(password)), "Password must never be stored in clear text");
            require(stored.contains("PBKDF2WithHmacSHA256"), "Password hashing algorithm");
            reject(() -> first.create("replacement", wrongPassword));
            require(stored.equals(Files.readString(firstFile)), "Existing administrator must not be replaced");

            AdministratorService second = new AdministratorService(secondFile);
            second.create("admin", password);
            require(!stored.equals(Files.readString(secondFile)), "Each administrator must have a random salt");
            Files.copy(firstFile, copiedFile);
            require(new AdministratorService(copiedFile).authenticate("admin", password),
                    "Credentials are portable and not tied to a Windows identity or path");

            ObjectMapper mapper = new ObjectMapper();
            var current = mapper.readTree(stored);
            var legacy = mapper.createObjectNode();
            legacy.put("version", 1);
            legacy.put("login", "legacy-admin");
            legacy.put("algorithm", current.get("algorithm").asText());
            legacy.put("iterations", current.get("iterations").asInt());
            legacy.put("salt", current.get("salt").asText());
            legacy.put("hash", current.get("hash").asText());
            Files.writeString(copiedFile, mapper.writeValueAsString(legacy));
            AdministratorService migrated = new AdministratorService(copiedFile);
            require(migrated.isConfigured(), "Existing administrator is migrated without asking for password");
            String migratedText = Files.readString(copiedFile);
            require(!migratedText.contains("legacy-admin") && !migratedText.contains("\"login\":"),
                    "Migration must remove clear-text login");
            require(migrated.authenticate("legacy-admin", password), "Migration preserves credentials");
            require(!migrated.authenticate("admin", password), "Migration validates login hash");
            require(migrated.isConfigured() && migratedText.equals(Files.readString(copiedFile)),
                    "Migrated record must not be rewritten on every startup");

            for (int i = 0; i < 5; i++) require(!first.authenticate("admin", wrongPassword), "Wrong password");
            reject(() -> first.authenticate("admin", password));

            Files.writeString(copiedFile, "{broken");
            AdministratorService corrupt = new AdministratorService(copiedFile);
            reject(corrupt::isConfigured);
            reject(() -> corrupt.authenticate("admin", password));
            reject(() -> corrupt.create("admin", password));
            require("{broken".equals(Files.readString(copiedFile)), "Corrupt registration must not be overwritten");
            Files.writeString(copiedFile, stored.replace("600000", "1"));
            reject(corrupt::isConfigured);
            Files.writeString(copiedFile, "x".repeat(5000));
            reject(corrupt::isConfigured);
            System.out.println("OK: hashed login/password, legacy migration, authentication, rate limit, corruption, portability");
        } finally {
            Arrays.fill(password, '\0');
            Arrays.fill(wrongPassword, '\0');
            Files.deleteIfExists(firstFile);
            Files.deleteIfExists(secondFile);
            Files.deleteIfExists(copiedFile);
            Files.deleteIfExists(directory);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void reject(Runnable action) {
        try { action.run(); }
        catch (IllegalArgumentException | IllegalStateException expected) { return; }
        throw new AssertionError("Expected rejection");
    }
}
