package br.com.jonmarques.vmslite.service;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public class OnvifDiscoveryService {
    private static final boolean DEBUG = Boolean.getBoolean("vmslite.debug");
    private static final long DISCOVERY_CACHE_MS = 30000;
    private static final long RECONNECT_DISCOVERY_MIN_INTERVAL_MS = 120_000;
    private static final Object DISCOVERY_LOCK = new Object();

    private static Map<String, DeviceInfo> cachedDevices = new HashMap<>();
    private static long cachedAt = 0;
    private static long lastReconnectTriggeredDiscovery = 0;
    private static CompletableFuture<Map<String, DeviceInfo>> discoveryInProgress;

    public static void discoverDevices(Consumer<Map<String, DeviceInfo>> callback) {
        discoverDevices(false, callback);
    }

    public static void discoverDevices(boolean forceRefresh, Consumer<Map<String, DeviceInfo>> callback) {
        deliver(discovery(forceRefresh, false), callback);
    }

    public static void discoverDevicesForReconnect(Consumer<Map<String, DeviceInfo>> callback) {
        deliver(discovery(false, true), callback);
    }

    private static void deliver(CompletableFuture<Map<String, DeviceInfo>> future,
                                Consumer<Map<String, DeviceInfo>> callback) {
        if (callback == null) return;
        future.thenAccept(devices -> callback.accept(new HashMap<>(devices)))
                .exceptionally(error -> {
                    System.err.println("Falha ao processar descoberta ONVIF: " + error.getClass().getSimpleName());
                    return null;
                });
    }

    private static CompletableFuture<Map<String, DeviceInfo>> discovery(boolean force, boolean reconnect) {
        synchronized (DISCOVERY_LOCK) {
            long now = System.currentTimeMillis();
            if (discoveryInProgress != null) return discoveryInProgress;
            boolean fresh = cachedAt != 0 && now - cachedAt < DISCOVERY_CACHE_MS;
            boolean limited = reconnect && now - lastReconnectTriggeredDiscovery < RECONNECT_DISCOVERY_MIN_INTERVAL_MS;
            if ((!force && fresh) || limited) {
                return CompletableFuture.completedFuture(new HashMap<>(cachedDevices));
            }
            if (reconnect) lastReconnectTriggeredDiscovery = now;
            discoveryInProgress = new CompletableFuture<>();
            startDiscoveryThread(discoveryInProgress);
            return discoveryInProgress;
        }
    }

    private static volatile String lastDiscoveryStatus = "";
    public static String getLastDiscoveryStatus() { return lastDiscoveryStatus; }

    private static void startDiscoveryThread(CompletableFuture<Map<String, DeviceInfo>> future) {
        Thread discoveryThread = new Thread(() -> {
            Map<String, DeviceInfo> found = new HashMap<>();
            try {
                WsDiscoveryScanner.Result result = WsDiscoveryScanner.scan();
                found.putAll(result.devices());
                lastDiscoveryStatus = "Interfaces pesquisadas: " + result.interfaces() + ". Dispositivos encontrados: "
                        + found.size() + "." + (result.errors().isEmpty() ? "" : " Avisos: " + String.join("; ", result.errors()));
                logDebug(lastDiscoveryStatus);
            } catch (RuntimeException error) {
                lastDiscoveryStatus = "Nao foi possivel concluir a busca: " + error.getClass().getSimpleName();
                System.err.println(lastDiscoveryStatus);
            } finally {
                synchronized (DISCOVERY_LOCK) {
                    cachedDevices = new HashMap<>(found);
                    cachedAt = found.isEmpty() ? 0 : System.currentTimeMillis();
                    if (discoveryInProgress == future) discoveryInProgress = null;
                }
                future.complete(found);
            }
        }, "OnvifDiscovery");
        discoveryThread.setDaemon(true);
        discoveryThread.start();
    }

    private static void logDebug(String message) {
        if (DEBUG) {
            System.out.println(message);
        }
    }

    public String obterUrlRtsp(String serviceUrl, String usuario, String senha, String modelo, String ip, boolean substream) {
        return new OnvifMediaService().obterUrlRtsp(serviceUrl, usuario, senha, modelo, ip, substream);
    }

    public static class DeviceInfo {
        private final String xaddr;
        private final String uuid;
        private final String name;

        public DeviceInfo(String xaddr, String uuid) {
            this(xaddr, uuid, null);
        }

        public DeviceInfo(String xaddr, String uuid, String name) {
            this.xaddr = xaddr;
            this.uuid = uuid;
            this.name = name;
        }

        public String getXaddr() { return xaddr; }
        public String getUuid() { return uuid; }
        public String getName() { return name; }
    }

    public static String encontrarIpPorUuid(Map<String, DeviceInfo> dispositivos, String uuidSalvo) {
        if (uuidSalvo == null || uuidSalvo.isBlank() || dispositivos == null) {
            return null;
        }
        for (Map.Entry<String, DeviceInfo> entry : dispositivos.entrySet()) {
            String uuidCandidato = entry.getValue().getUuid();
            if (uuidCandidato != null && uuidCandidato.equalsIgnoreCase(uuidSalvo)) {
                return entry.getKey();
            }
        }
        return null;
    }

    public static String substituirIpNaUrl(String url, String ip) { return CameraAddress.replaceHost(url, ip); }
    public static String extrairIpDaUrl(String url) { return CameraAddress.host(url); }
}
