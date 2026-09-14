package br.com.jonmarques.vmslite.service;

import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** A bounded scan across IPv4 interfaces; replies return to each probe's source port. */
final class WsDiscoveryScanner {
    static final String DISCOVERY = "http://schemas.xmlsoap.org/ws/2005/04/discovery";
    static final String ADDRESSING = "http://schemas.xmlsoap.org/ws/2004/08/addressing";
    record Binding(NetworkInterface network, InetAddress address) {}
    record Result(Map<String, OnvifDiscoveryService.DeviceInfo> devices, List<String> errors, int interfaces) {}

    static Result scan() {
        List<Binding> bindings = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        try {
            var networks = NetworkInterface.getNetworkInterfaces();
            while (networks != null && networks.hasMoreElements()) {
                NetworkInterface network = networks.nextElement();
                try {
                    if (!network.isUp() || network.isLoopback() || !network.supportsMulticast()) continue;
                    for (InetAddress address : Collections.list(network.getInetAddresses())) {
                        if (address instanceof Inet4Address && !address.isLoopbackAddress())
                            bindings.add(new Binding(network, address));
                    }
                } catch (SocketException error) { errors.add(network.getName() + ": " + error.getClass().getSimpleName()); }
            }
            Result result = scan(bindings, new InetSocketAddress("239.255.255.250", 3702), 6000);
            errors.addAll(result.errors());
            if (bindings.isEmpty()) errors.add("Nenhuma interface IPv4 ativa com multicast disponivel.");
            return new Result(result.devices(), List.copyOf(errors), result.interfaces());
        } catch (IOException | SecurityException error) {
            errors.add("Acesso a rede: " + error.getClass().getSimpleName());
            return new Result(Map.of(), List.copyOf(errors), 0);
        }
    }

    static Result scan(List<Binding> bindings, InetSocketAddress destination, long durationMs) throws IOException {
        Map<String, OnvifDiscoveryService.DeviceInfo> devices = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        List<DatagramChannel> channels = new ArrayList<>();
        try (Selector selector = Selector.open()) {
            for (Binding binding : bindings) {
                DatagramChannel channel = null;
                try {
                    channel = DatagramChannel.open(StandardProtocolFamily.INET);
                    channel.bind(new InetSocketAddress(binding.address(), 0));
                    if (binding.network() != null) {
                        channel.setOption(StandardSocketOptions.IP_MULTICAST_IF, binding.network());
                        channel.setOption(StandardSocketOptions.IP_MULTICAST_TTL, 1);
                    }
                    channel.configureBlocking(false);
                    channel.register(selector, SelectionKey.OP_READ);
                    channels.add(channel);
                } catch (IOException | SecurityException error) {
                    errors.add(binding.address().getHostAddress() + ": " + error.getClass().getSimpleName());
                    if (channel != null) channel.close();
                }
            }
            long start = System.nanoTime();
            long deadline = start + TimeUnit.MILLISECONDS.toNanos(durationMs);
            int round = 0;
            ByteBuffer buffer = ByteBuffer.allocate(65507);
            // Same message IDs on retransmission allow device-side duplicate suppression.
            byte[][] probes = { probe(false).getBytes(StandardCharsets.UTF_8), probe(true).getBytes(StandardCharsets.UTF_8) };
            while (!channels.isEmpty() && System.nanoTime() < deadline) {
                long now = System.nanoTime();
                if (round < 3 && now >= start + TimeUnit.MILLISECONDS.toNanos(round * 750L)) {
                    for (DatagramChannel channel : channels) {
                        for (byte[] probe : probes) {
                            try { channel.send(ByteBuffer.wrap(probe), destination); }
                            catch (IOException error) {
                                String message = channel.getLocalAddress() + ": envio " + error.getClass().getSimpleName();
                                if (!errors.contains(message)) errors.add(message);
                            }
                        }
                    }
                    round++;
                }
                long wake = round < 3 ? Math.min(deadline, start + TimeUnit.MILLISECONDS.toNanos(round * 750L)) : deadline;
                selector.select(Math.max(1, TimeUnit.NANOSECONDS.toMillis(wake - System.nanoTime())));
                var keys = selector.selectedKeys().iterator();
                while (keys.hasNext()) {
                    SelectionKey key = keys.next();
                    keys.remove();
                    DatagramChannel channel = (DatagramChannel) key.channel();
                    try {
                        buffer.clear();
                        InetSocketAddress sender = (InetSocketAddress) channel.receive(buffer);
                        if (sender == null) continue;
                        buffer.flip();
                        parseResponse(StandardCharsets.UTF_8.decode(buffer).toString(), sender.getAddress().getHostAddress(), devices);
                    } catch (IOException error) {
                        String message = "Recepcao: " + error.getClass().getSimpleName();
                        if (!errors.contains(message)) errors.add(message);
                    }
                }
            }
            return new Result(Map.copyOf(devices), List.copyOf(errors), channels.size());
        } finally {
            for (DatagramChannel channel : channels) {
                try { channel.close(); } catch (IOException ignored) { }
            }
        }
    }

    static String probe(boolean deviceType) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\" xmlns:a=\"" + ADDRESSING
                + "\" xmlns:d=\"" + DISCOVERY + "\" xmlns:dn=\"http://www.onvif.org/ver10/network/wsdl\""
                + " xmlns:tds=\"http://www.onvif.org/ver10/device/wsdl\"><s:Header>"
                + "<a:MessageID>urn:uuid:" + UUID.randomUUID() + "</a:MessageID>"
                + "<a:To s:mustUnderstand=\"1\">urn:schemas-xmlsoap-org:ws:2005:04:discovery</a:To>"
                + "<a:Action s:mustUnderstand=\"1\">" + DISCOVERY + "/Probe</a:Action>"
                + "<a:ReplyTo><a:Address>" + ADDRESSING + "/role/anonymous</a:Address></a:ReplyTo>"
                + "</s:Header><s:Body><d:Probe><d:Types>" + (deviceType ? "tds:Device" : "dn:NetworkVideoTransmitter")
                + "</d:Types></d:Probe></s:Body></s:Envelope>";
    }

    static void parseResponse(String response, String sender, Map<String, OnvifDiscoveryService.DeviceInfo> devices) {
        var xml = OnvifXml.parse(response);
        for (var match : OnvifXml.elements(xml, "ProbeMatch")) {
            if (!DISCOVERY.equals(match.getNamespaceURI())) continue;
            var previous = devices.get(sender);
            String xaddr = selectAddress(OnvifXml.text(match, "XAddrs"), sender);
            if (xaddr == null) xaddr = previous == null ? "http://" + sender + "/onvif/device_service" : previous.getXaddr();
            String uuid = OnvifXml.deviceUuid(match);
            if (previous != null && uuid == null) uuid = previous.getUuid();
            String name = deviceName(OnvifXml.text(match, "Scopes"));
            if (name == null && previous != null) name = previous.getName();
            devices.put(sender, new OnvifDiscoveryService.DeviceInfo(xaddr, uuid, name));
        }
    }

    static String deviceName(String scopes) {
        if (scopes == null || scopes.isBlank()) return null;
        for (String scope : scopes.trim().split("\\s+")) {
            try {
                URI uri = URI.create(scope);
                String path = uri.getPath();
                if ("onvif".equalsIgnoreCase(uri.getScheme()) && "www.onvif.org".equalsIgnoreCase(uri.getHost())
                        && path != null && path.startsWith("/name/")) {
                    String name = path.substring(6).replaceAll("\\p{Cntrl}", " ").trim();
                    if (!name.isBlank()) return name;
                }
            } catch (IllegalArgumentException ignored) { }
        }
        return null;
    }

    static String selectAddress(String addresses, String sender) {
        if (addresses == null || addresses.isBlank()) return null;
        String fallback = null;
        for (String address : addresses.trim().split("\\s+")) {
            try {
                URI uri = URI.create(address);
                if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                        || uri.getHost() == null || uri.getUserInfo() != null) continue;
                if (sender.equalsIgnoreCase(uri.getHost())) return address;
                if (fallback == null) fallback = address;
            } catch (IllegalArgumentException ignored) { }
        }
        return fallback;
    }
}
