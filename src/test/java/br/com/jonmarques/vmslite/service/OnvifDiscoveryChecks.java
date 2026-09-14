package br.com.jonmarques.vmslite.service;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

public final class OnvifDiscoveryChecks {
    private static int checks;
    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--network")) {
            var result = WsDiscoveryScanner.scan();
            System.out.println("Interfaces: " + result.interfaces() + "; cameras: " + result.devices().size());
            result.errors().forEach(System.out::println);
            return;
        }
        messages();
        responses();
        names();
        localDevice();
        System.out.println("OK: " + checks + " discovery checks (headers, addresses, XML, UDP replies, retry, timeout)");
    }

    private static void messages() {
        var probe = OnvifXml.parse(WsDiscoveryScanner.probe(false));
        check(probe != null, "Valid probe XML");
        check("urn:schemas-xmlsoap-org:ws:2005:04:discovery".equals(OnvifXml.text(probe, "To")), "Discovery destination");
        check(OnvifXml.elements(probe, "MessageID").get(0).getNamespaceURI().equals(WsDiscoveryScanner.ADDRESSING), "Addressing version");
        check(OnvifXml.text(probe, "MessageID").startsWith("urn:uuid:"), "Message identity");
        check(!OnvifXml.text(probe, "MessageID").equals(OnvifXml.text(OnvifXml.parse(WsDiscoveryScanner.probe(false)), "MessageID")), "Unique scan identity");
        check(OnvifXml.text(probe, "Address").endsWith("/role/anonymous"), "Unicast reply address");
        check(OnvifXml.text(probe, "Types").equals("dn:NetworkVideoTransmitter"), "Video type");
        check(OnvifXml.text(OnvifXml.parse(WsDiscoveryScanner.probe(true)), "Types").equals("tds:Device"), "Device type");
    }

    private static String response(String sender) {
        return "<s:Envelope xmlns:s='http://www.w3.org/2003/05/soap-envelope' xmlns:d='"
                + WsDiscoveryScanner.DISCOVERY + "' xmlns:a='" + WsDiscoveryScanner.ADDRESSING
                + "'><s:Body><d:ProbeMatches><d:ProbeMatch><a:EndpointReference><a:Address>urn:uuid:test-camera"
                + "</a:Address></a:EndpointReference><d:XAddrs>http://192.0.2.1/wrong http://" + sender
                + ":8080/onvif/device_service</d:XAddrs></d:ProbeMatch></d:ProbeMatches></s:Body></s:Envelope>";
    }

    private static void responses() {
        Map<String, OnvifDiscoveryService.DeviceInfo> found = new HashMap<>();
        WsDiscoveryScanner.parseResponse("not xml", "10.0.0.5", found);
        WsDiscoveryScanner.parseResponse("<Envelope><XAddrs>http://host/service</XAddrs></Envelope>", "10.0.0.5", found);
        check(found.isEmpty(), "Ignore malformed and unrelated UDP");
        WsDiscoveryScanner.parseResponse(response("10.0.0.5"), "10.0.0.5", found);
        check(found.size() == 1 && found.get("10.0.0.5").getUuid().equals("test-camera"), "Parse endpoint identity");
        check(found.get("10.0.0.5").getXaddr().equals("http://10.0.0.5:8080/onvif/device_service"), "Prefer responding interface endpoint");
        WsDiscoveryScanner.parseResponse(response("10.0.0.5"), "10.0.0.5", found);
        check(found.size() == 1, "Deduplicate replies");
        check(WsDiscoveryScanner.selectAddress("file:///private invalid", "10.0.0.5") == null, "Reject invalid endpoint schemes");
    }

    private static void localDevice() throws Exception {
        InetAddress local = InetAddress.getByName("127.0.0.1");
        try (DatagramSocket device = new DatagramSocket(new InetSocketAddress(local, 0))) {
            device.setSoTimeout(4000);
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<Integer> responder = executor.submit(() -> {
                    int requests = 0;
                    while (requests < 4) {
                        DatagramPacket packet = new DatagramPacket(new byte[8192], 8192);
                        device.receive(packet);
                        var xml = OnvifXml.parse(new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8));
                        if (!"urn:schemas-xmlsoap-org:ws:2005:04:discovery".equals(OnvifXml.text(xml, "To"))
                                || OnvifXml.text(xml, "MessageID") == null) throw new AssertionError("Invalid on-wire headers");
                        requests++;
                        // Drop the first round to simulate packet loss.
                        if (requests > 2) {
                            byte[] reply = response("127.0.0.1").getBytes(StandardCharsets.UTF_8);
                            device.send(new DatagramPacket(reply, reply.length, packet.getSocketAddress()));
                        }
                    }
                    return requests;
                });
                long start = System.nanoTime();
                var result = WsDiscoveryScanner.scan(List.of(new WsDiscoveryScanner.Binding(null, local)),
                        new InetSocketAddress(local, device.getLocalPort()), 1800);
                check(result.devices().containsKey("127.0.0.1"), "Receive camera UDP reply after dropped first probe");
                check(responder.get(4, TimeUnit.SECONDS) == 4, "Bounded retransmission");
                check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 4000, "Global deadline");
                check(result.errors().isEmpty(), "No transport errors");
            } finally { executor.shutdownNow(); executor.awaitTermination(5, TimeUnit.SECONDS); }
        }
    }

    private static void names() {
        check("Entrada Principal".equals(WsDiscoveryScanner.deviceName(
                "onvif://www.onvif.org/hardware/Model onvif://www.onvif.org/name/Entrada%20Principal")), "Decode device name");
        check("Portaria+1".equals(WsDiscoveryScanner.deviceName("onvif://www.onvif.org/name/Portaria+1")), "Preserve literal plus");
        check("Recep\u00e7\u00e3o".equals(WsDiscoveryScanner.deviceName("onvif://www.onvif.org/name/Recep%C3%A7%C3%A3o")), "UTF-8 device name");
        check(WsDiscoveryScanner.deviceName("onvif://www.onvif.org/name/%XX onvif://www.onvif.org/hardware/Model") == null,
                "Ignore malformed and non-name scopes");
        check(WsDiscoveryScanner.deviceName(null) == null, "Missing name");
        Map<String, OnvifDiscoveryService.DeviceInfo> found = new HashMap<>();
        String named = response("10.0.0.5").replace("</d:ProbeMatch>",
                "<d:Scopes>onvif://www.onvif.org/name/Entrada%20Principal</d:Scopes></d:ProbeMatch>");
        WsDiscoveryScanner.parseResponse(named, "10.0.0.5", found);
        check("Entrada Principal".equals(found.get("10.0.0.5").getName()), "Name attached to discovered device");
        WsDiscoveryScanner.parseResponse(response("10.0.0.5"), "10.0.0.5", found);
        check("Entrada Principal".equals(found.get("10.0.0.5").getName()), "Keep name on partial duplicate reply");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}
