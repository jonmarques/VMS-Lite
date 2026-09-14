package br.com.jonmarques.vmslite.service;

import br.com.jonmarques.vmslite.entity.Camera;
import br.com.jonmarques.vmslite.entity.VMSConfig;
import br.com.jonmarques.vmslite.entity.CameraTourConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.util.ArrayList;

public final class ConfigService {
    private static final Path DEFAULT_FILE = Path.of(System.getProperty("user.home"), ".vmslite", "vms-config.json");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ConfigService() {}

    public static VMSConfig snapshot(VMSConfig source) {
        validate(source);
        VMSConfig copy = new VMSConfig(source.getLayoutCols(), source.getLayoutRows(),
                new ArrayList<>(source.getCameras().stream().map(camera -> {
                    Camera item = new Camera(camera.getName(), camera.getUrl(), camera.getUuid(),
                            camera.getRowSpan(), camera.getColSpan());
                    item.setId(camera.getId());
                    return item;
                }).toList()));
        copy.setCameraTour(source.getCameraTour().copy());
        return copy;
    }

    public static void validate(VMSConfig config) {
        if (config == null || config.getCameras() == null) throw new IllegalArgumentException("Configuracao vazia");
        dimension(config.getLayoutRows());
        dimension(config.getLayoutCols());
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (Camera camera : config.getCameras()) {
            if (camera == null || CameraAddress.host(camera.getUrl()) == null) {
                throw new IllegalArgumentException("Endereco de camera invalido");
            }
            dimension(camera.getRowSpan());
            dimension(camera.getColSpan());
            if (camera.getId() == null || camera.getId().isBlank() || !ids.add(camera.getId())) {
                throw new IllegalArgumentException("Identificador de camera invalido ou duplicado");
            }
        }
        CameraTourConfig tour = config.getCameraTour();
        if (tour == null || tour.getCameraIds() == null
                || tour.getIntervalSeconds() < CameraTourConfig.MIN_INTERVAL_SECONDS
                || tour.getIntervalSeconds() > CameraTourConfig.MAX_INTERVAL_SECONDS
                || !ids.containsAll(tour.getCameraIds())
                || new java.util.HashSet<>(tour.getCameraIds()).size() != tour.getCameraIds().size()
                || (tour.isEnabled() && tour.getCameraIds().isEmpty())) {
            throw new IllegalArgumentException("Configuracao de tour invalida");
        }
    }

    public static int dimension(int value) {
        if (value < 1 || value > 20) throw new IllegalArgumentException("Dimensoes devem estar entre 1 e 20");
        return value;
    }

    public static void save(VMSConfig config) { saveToFile(config, DEFAULT_FILE.toFile()); }

    public static synchronized void saveToFile(VMSConfig config, File file) {
        validate(config);
        Path target = file.toPath().toAbsolutePath();
        Path temporary = null;
        try {
            Files.createDirectories(target.getParent());
            temporary = Files.createTempFile(target.getParent(), ".vms-config-", ".tmp");
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), config);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Erro ao gravar configuracao", e);
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
            }
        }
    }

    public static VMSConfig load() {
        if (!Files.exists(DEFAULT_FILE)) return new VMSConfig();
        return loadFromFile(DEFAULT_FILE.toFile());
    }

    public static VMSConfig loadFromFile(File file) {
        try {
            VMSConfig config = MAPPER.readValue(file, VMSConfig.class);
            validate(config);
            return config;
        } catch (IOException e) {
            throw new UncheckedIOException("Erro ao ler configuracao", e);
        }
    }
}
