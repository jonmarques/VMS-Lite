package br.com.jonmarques.vmslite.service;

import java.net.*;
import java.nio.charset.StandardCharsets;
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

    private static void startDiscoveryThread(CompletableFuture<Map<String, DeviceInfo>> future) {
        Thread discoveryThread = new Thread(() -> {
            logDebug("Iniciando descoberta de dispositivos ONVIF (WS-Discovery)...");
            Map<String, DeviceInfo> dispositivosEncontrados = new HashMap<>();
            DatagramSocket socket = null;

            try {
                socket = new DatagramSocket();
                socket.setSoTimeout(4000);

                String probeRequest = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                        + "<s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\" xmlns:a=\"http://www.w3.org/2005/08/addressing\">"
                        + "<s:Header>"
                        + "<a:Action s:mustUnderstand=\"1\">http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</a:Action>"
                        + "<a:To s:mustUnderstand=\"1\">urn:schemas-xmlsoap.org:ws:2005/04/discovery</a:To>"
                        + "</s:Header>"
                        + "<s:Body>"
                        + "<Probe xmlns=\"http://schemas.xmlsoap.org/ws/2005/04/discovery\">"
                        + "<Types xmlns:dn=\"http://www.onvif.org/ver10/network/wsdl\">dn:NetworkVideoTransmitter</Types>"
                        + "</Probe>"
                        + "</s:Body>"
                        + "</s:Envelope>";

                byte[] sendData = probeRequest.getBytes(StandardCharsets.UTF_8);
                InetAddress multicastAddress = InetAddress.getByName("239.255.255.250");

                DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, multicastAddress, 3702);
                socket.send(sendPacket);

                byte[] recvBuf = new byte[8192];
                long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(4);
                while (System.nanoTime() < deadline) {
                    socket.setSoTimeout((int) Math.max(1, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())));
                    try {
                        DatagramPacket receivePacket = new DatagramPacket(recvBuf, recvBuf.length);
                        socket.receive(receivePacket);

                        String response = new String(receivePacket.getData(), 0, receivePacket.getLength(), StandardCharsets.UTF_8);
                        String deviceIp = receivePacket.getAddress().getHostAddress();

                        if (!dispositivosEncontrados.containsKey(deviceIp)) {
                            logDebug("Dispositivo ONVIF respondendo no IP: " + deviceIp);
                            var xml = OnvifXml.parse(response);
                            String xaddr = OnvifXml.text(xml, "XAddrs");
                            if (xaddr != null) xaddr = xaddr.split("\\s+")[0];
                            if (xaddr == null || xaddr.isBlank()) {
                                xaddr = "http://" + deviceIp + "/onvif/device_service";
                            }

                            String uuid = OnvifXml.deviceUuid(xml);
                            if (uuid != null) {
                                logDebug("UUID do dispositivo " + deviceIp + ": " + uuid);
                            } else {
                                logDebug("Dispositivo " + deviceIp + " nao retornou UUID no ProbeMatch.");
                            }

                            dispositivosEncontrados.put(deviceIp, new DeviceInfo(xaddr, uuid));
                        }
                    } catch (java.net.SocketTimeoutException e) {
                        logDebug("Varredura de rede finalizada.");
                        break;
                    }
                }
            } catch (Exception e) {
                System.err.println("Erro na descoberta de dispositivos: " + e.getMessage());
            } finally {
                if (socket != null && !socket.isClosed()) {
                    socket.close();
                }

                synchronized (DISCOVERY_LOCK) {
                    cachedDevices = new HashMap<>(dispositivosEncontrados);
                    cachedAt = System.currentTimeMillis();
                    if (discoveryInProgress == future) {
                        discoveryInProgress = null;
                    }
                }
                future.complete(dispositivosEncontrados);
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

        public DeviceInfo(String xaddr, String uuid) {
            this.xaddr = xaddr;
            this.uuid = uuid;
        }

        public String getXaddr() { return xaddr; }
        public String getUuid() { return uuid; }
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
