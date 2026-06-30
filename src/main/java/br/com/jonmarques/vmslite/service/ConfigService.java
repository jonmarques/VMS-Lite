package br.com.jonmarques.vmslite.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import br.com.jonmarques.vmslite.entity.VMSConfig;

import java.io.File;
import java.util.ArrayList;

public class ConfigService {

    private static final String DIR_NAME = ".vmslite";
    private static final String FILE_NAME = "vms-config.json";

    private static final File BASE_DIR =
            new File(System.getProperty("user.home"), DIR_NAME);

    private static final File FILE =
            new File(BASE_DIR, FILE_NAME);

    private static final ObjectMapper mapper =
            new ObjectMapper();

    // =========================
    // SAVE padrão (arquivo fixo)
    // =========================
    public static void save(VMSConfig config) {

        try {
            ensureDir();

            mapper.writerWithDefaultPrettyPrinter()
                    .writeValue(FILE, config);

            System.out.println("Config salva em: " + FILE.getAbsolutePath());

        } catch (Exception e) {
            System.err.println("Erro ao salvar config padrão:");
            e.printStackTrace();
        }
    }

    // =========================
    // SAVE em arquivo externo
    // =========================
    public static void saveToFile(VMSConfig config, File file) {

        try {
            if (file == null) return;

            File parent = file.getParentFile();
            if (parent != null) parent.mkdirs();

            mapper.writerWithDefaultPrettyPrinter()
                    .writeValue(file, config);

            System.out.println("Config exportada em: " + file.getAbsolutePath());

        } catch (Exception e) {
            System.err.println("Erro ao exportar config:");
            e.printStackTrace();
        }
    }

    // =========================
    // LOAD padrão
    // =========================
    public static VMSConfig load() {

        try {
            if (!FILE.exists()) {
                VMSConfig defaultConfig =
                        new VMSConfig(2, 2, new ArrayList<>());

                save(defaultConfig);
                return defaultConfig;
            }

            return mapper.readValue(FILE, VMSConfig.class);

        } catch (Exception e) {
            System.err.println("Erro ao carregar config padrão:");
            e.printStackTrace();

            return new VMSConfig(2, 2, new ArrayList<>());
        }
    }

    // =========================
    // LOAD de arquivo externo
    // =========================
    public static VMSConfig loadFromFile(File file) {

        try {
            if (file == null) {
                return new VMSConfig(2, 2, new ArrayList<>());
            }

            if (!file.exists()) {
                VMSConfig defaultConfig =
                        new VMSConfig(2, 2, new ArrayList<>());

                saveToFile(defaultConfig, file);
                return defaultConfig;
            }

            return mapper.readValue(file, VMSConfig.class);

        } catch (Exception e) {
            System.err.println("Erro ao carregar config externa:");
            e.printStackTrace();

            return new VMSConfig(2, 2, new ArrayList<>());
        }
    }

    // =========================
    // util
    // =========================
    private static void ensureDir() {
        if (!BASE_DIR.exists()) {
            BASE_DIR.mkdirs();
        }
    }
}