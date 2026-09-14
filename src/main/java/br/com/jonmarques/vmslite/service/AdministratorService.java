package br.com.jonmarques.vmslite.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.TimeUnit;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

public final class AdministratorService {
    public static final int MIN_PASSWORD_LENGTH = 12;
    private static final int ITERATIONS = 600_000;
    private static final int MAX_FILE_BYTES = 4096;
    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final AdministratorService DEFAULT = new AdministratorService(
            Path.of(System.getProperty("user.home"), ".vmslite", "admin-auth.json"));
    private final Path file;
    private int failedAttempts;
    private long blockedUntil;

    public AdministratorService(Path file) { this.file = file.toAbsolutePath(); }
    public static AdministratorService getDefault() { return DEFAULT; }

    public synchronized boolean isConfigured() {
        if (Files.notExists(file)) return false;
        readRecord();
        return true;
    }

    public synchronized void create(String login, char[] password) {
        String normalizedLogin = login == null ? "" : login.trim();
        if (normalizedLogin.isEmpty() || normalizedLogin.length() > 128) {
            throw new IllegalArgumentException("Informe um login de ate 128 caracteres.");
        }
        if (password == null || password.length < MIN_PASSWORD_LENGTH || password.length > 1024) {
            throw new IllegalArgumentException("Use uma senha de 12 a 1024 caracteres.");
        }
        if (!Files.notExists(file)) {
            throw new IllegalStateException("Ja existe um cadastro de administrador. Ele nao foi alterado.");
        }
        byte[] salt = new byte[32];
        RANDOM.nextBytes(salt);
        byte[] hash = derive(password, salt);
        try {
            Record record = protectLogin(normalizedLogin,
                    Base64.getEncoder().encodeToString(salt), Base64.getEncoder().encodeToString(hash));
            byte[] encoded = MAPPER.writeValueAsBytes(record);
            Files.createDirectories(file.getParent());
            // CREATE_NEW also prevents a concurrent registration from replacing an existing administrator.
            Files.write(file, encoded, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                    StandardOpenOption.SYNC);
        } catch (IOException e) {
            throw new IllegalStateException("Nao foi possivel salvar o administrador. Nenhum cadastro foi substituido.");
        } finally {
            Arrays.fill(hash, (byte) 0);
            Arrays.fill(salt, (byte) 0);
        }
    }

    public synchronized boolean authenticate(String login, char[] password) {
        if (blockedUntil != 0 && System.nanoTime() < blockedUntil) {
            throw new IllegalStateException("Muitas tentativas. Aguarde 30 segundos antes de tentar novamente.");
        }
        Record record = readRecord();
        String normalizedLogin = login == null ? "" : login.trim();
        if (password == null || password.length > 1024 || normalizedLogin.length() > 128) return failed();
        byte[] expected = Base64.getDecoder().decode(record.hash());
        byte[] salt = Base64.getDecoder().decode(record.salt());
        byte[] actual = derive(password, salt);
        try {
            boolean passwordMatches = MessageDigest.isEqual(expected, actual);
            boolean loginMatches = matchesLogin(normalizedLogin, record);
            if (!(passwordMatches & loginMatches)) return failed();
            failedAttempts = 0;
            blockedUntil = 0;
            return true;
        } finally {
            Arrays.fill(expected, (byte) 0);
            Arrays.fill(actual, (byte) 0);
            Arrays.fill(salt, (byte) 0);
        }
    }

    private boolean failed() {
        if (++failedAttempts >= 5) {
            blockedUntil = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            failedAttempts = 0;
        }
        return false;
    }

    private Record readRecord() {
        try {
            byte[] encoded;
            try (var input = Files.newInputStream(file)) {
                encoded = input.readNBytes(MAX_FILE_BYTES + 1);
            }
            if (encoded.length > MAX_FILE_BYTES) throw new IOException();
            var root = MAPPER.readTree(encoded);
            if (root == null || !root.isObject()) throw new IOException();
            if (root.path("version").asInt() == 1) {
                LegacyRecord legacy = MAPPER.treeToValue(root, LegacyRecord.class);
                if (!validPasswordRecord(legacy.algorithm(), legacy.iterations(), legacy.salt(), legacy.hash())
                        || legacy.login() == null || legacy.login().isBlank() || legacy.login().length() > 128) {
                    throw new IOException();
                }
                Record migrated = protectLogin(legacy.login(), legacy.salt(), legacy.hash());
                replaceRecord(migrated);
                return migrated;
            }
            Record record = MAPPER.treeToValue(root, Record.class);
            if (record.version() != 2
                    || !validPasswordRecord(record.algorithm(), record.iterations(), record.salt(), record.hash())
                    || !validDigest(record.loginSalt()) || !validDigest(record.loginHash())) {
                throw new IOException();
            }
            return record;
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("Nao foi possivel ler o cadastro de administrador. A visualizacao foi bloqueada.");
        }
    }

    private static boolean validPasswordRecord(String algorithm, int iterations, String salt, String hash) {
        return ALGORITHM.equals(algorithm) && iterations == ITERATIONS && validDigest(salt) && validDigest(hash);
    }

    private static boolean validDigest(String value) {
        return value != null && Base64.getDecoder().decode(value).length == 32;
    }

    private static Record protectLogin(String login, String passwordSalt, String passwordHash) {
        byte[] salt = new byte[32];
        RANDOM.nextBytes(salt);
        char[] characters = login.toCharArray();
        byte[] hash = null;
        try {
            hash = derive(characters, salt);
            return new Record(2, ALGORITHM, ITERATIONS, passwordSalt, passwordHash,
                    Base64.getEncoder().encodeToString(salt), Base64.getEncoder().encodeToString(hash));
        } finally {
            Arrays.fill(characters, '\0');
            Arrays.fill(salt, (byte) 0);
            if (hash != null) Arrays.fill(hash, (byte) 0);
        }
    }

    private static boolean matchesLogin(String login, Record record) {
        byte[] salt = Base64.getDecoder().decode(record.loginSalt());
        byte[] expected = Base64.getDecoder().decode(record.loginHash());
        char[] characters = login.toCharArray();
        byte[] actual = null;
        try {
            actual = derive(characters, salt);
            return MessageDigest.isEqual(expected, actual);
        } finally {
            Arrays.fill(characters, '\0');
            Arrays.fill(salt, (byte) 0);
            Arrays.fill(expected, (byte) 0);
            if (actual != null) Arrays.fill(actual, (byte) 0);
        }
    }

    private void replaceRecord(Record record) throws IOException {
        Path temporary = Files.createTempFile(file.getParent(), ".admin-auth-", ".tmp");
        try {
            Files.write(temporary, MAPPER.writeValueAsBytes(record), StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.SYNC);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static byte[] derive(char[] password, byte[] salt) {
        PBEKeySpec specification = new PBEKeySpec(password, salt, ITERATIONS, 256);
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(specification).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Nao foi possivel validar a senha com seguranca.");
        } finally {
            specification.clearPassword();
        }
    }

    public record Record(int version, String algorithm, int iterations, String salt, String hash,
                         String loginSalt, String loginHash) {}
    private record LegacyRecord(int version, String login, String algorithm, int iterations, String salt, String hash) {}
}
