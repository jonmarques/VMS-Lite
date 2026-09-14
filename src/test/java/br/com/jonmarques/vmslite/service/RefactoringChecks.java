package br.com.jonmarques.vmslite.service;

import br.com.jonmarques.vmslite.entity.Camera;
import br.com.jonmarques.vmslite.entity.VMSConfig;
import br.com.jonmarques.vmslite.ui.CameraGridLayout;
import java.awt.Rectangle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public final class RefactoringChecks {
    private static int checks;

    public static void main(String[] args) throws Exception {
        addresses();
        grid();
        configuration();
        xmlAndStreams();
        System.out.println("OK: " + checks + " checks (URLs, grid, configuration, XML, substreams)");
    }

    private static void addresses() {
        String original = "rtsp://admin:192.168.1.9%40pass@10.0.0.2:554/sub?source=192.168.2.1";
        check("10.0.0.2".equals(CameraAddress.host(original)), "Only extract the host");
        check(CameraAddress.replaceHost(original, "10.0.0.3").equals(
                "rtsp://admin:192.168.1.9%40pass@10.0.0.3:554/sub?source=192.168.2.1"), "Preserve credentials and query");
        check(CameraAddress.host("invalid address") == null, "Invalid address");
        check(CameraAddress.replaceHost("rtsp://camera.local/live", "2001:db8::1")
                .equals("rtsp://[2001:db8::1]/live"), "IPv6 replacement");
    }

    private static void grid() {
        Random random = new Random(71);
        for (int trial = 0; trial < 200; trial++) {
            int columns = 1 + random.nextInt(20);
            List<Camera> cameras = new ArrayList<>();
            for (int i = 0; i < 50; i++) cameras.add(new Camera("camera", "rtsp://host/live", null,
                    1 + random.nextInt(20), 1 + random.nextInt(20)));
            List<Rectangle> positions = CameraGridLayout.positions(cameras, columns);
            check(positions.size() == cameras.size(), "Every camera positioned");
            for (int i = 0; i < positions.size(); i++) {
                Rectangle position = positions.get(i);
                check(position.x >= 0 && position.y >= 0 && position.x + position.width <= columns, "Inside columns");
                for (int j = 0; j < i; j++) check(!position.intersects(positions.get(j)), "No overlap");
            }
        }
        check(CameraGridLayout.positions(List.of(), 0).isEmpty(), "Empty grid");
    }

    private static void configuration() throws Exception {
        Path folder = Files.createTempDirectory(Path.of("target"), "config-check-");
        Path file = folder.resolve("config.json");
        try {
            Camera camera = new Camera("first", "rtsp://host/live", "uuid", 2, 1);
            VMSConfig config = new VMSConfig(2, 2, new ArrayList<>(List.of(camera)));
            VMSConfig snapshot = ConfigService.snapshot(config);
            camera.setName("changed");
            check(snapshot.getCameras().get(0).getName().equals("first"), "Deep snapshot");
            ConfigService.saveToFile(snapshot, file.toFile());
            check(ConfigService.loadFromFile(file.toFile()).getCameras().get(0).getUuid().equals("uuid"), "JSON roundtrip");
            String previous = Files.readString(file);
            snapshot.getCameras().get(0).setRowSpan(Integer.MAX_VALUE);
            expectFailure(() -> ConfigService.saveToFile(snapshot, file.toFile()));
            check(Files.readString(file).equals(previous), "Invalid write preserves file");
            Files.writeString(file, "{broken");
            expectFailure(() -> ConfigService.loadFromFile(file.toFile()));
            check(Files.readString(file).equals("{broken"), "Invalid import preserves source");
            Files.writeString(file, "{\"cameras\":[{\"name\":\"old\",\"url\":\"rtsp://host/live\"}]}");
            check(ConfigService.loadFromFile(file.toFile()).getCameras().get(0).getRowSpan() == 1, "Missing span default");
            Files.writeString(file, "{\"cameras\":null}");
            expectFailure(() -> ConfigService.loadFromFile(file.toFile()));
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(folder);
        }
    }

    private static void xmlAndStreams() {
        var root = OnvifXml.parse("<s:Envelope xmlns:s='urn:soap' xmlns:t='urn:test'><t:Uri>rtsp://host/a?x=1&amp;y=2</t:Uri></s:Envelope>");
        check("rtsp://host/a?x=1&y=2".equals(OnvifXml.text(root, "Uri")), "Namespaces and XML entities");
        check(OnvifXml.parse("<!DOCTYPE x [<!ENTITY a SYSTEM 'file:///missing'>]><x>&a;</x>") == null, "DTD rejected");
        check(OnvifXml.parse("<broken") == null, "Malformed XML");
        check("camera-id".equals(OnvifXml.deviceUuid(OnvifXml.parse(
                "<Envelope><Address>anonymous</Address><Address>urn:uuid:camera-id</Address></Envelope>"))),
                "Skip non-device addressing headers");
        OnvifMediaService service = new OnvifMediaService();
        check(service.obterUrlRtsp(null, "admin", "a@ b", "hikvision", "host", true).endsWith("/102"), "Hikvision substream");
        check(service.obterUrlRtsp(null, "admin", "pw", "dahua", "host", true).endsWith("subtype=1"), "Dahua substream");
        check(service.obterUrlRtsp(null, "admin", "pw", "tapo", "host", true).endsWith("/stream2"), "Tapo substream");
        check(service.obterUrlRtsp(null, "admin", "pw", "reolink", "host", true).endsWith("_sub"), "Reolink substream");
        check(service.obterUrlRtsp(null, "admin", "pw", "hikvision", "host", false).endsWith("/101"), "Mainstream preserved");
    }

    private static void expectFailure(Runnable action) {
        try { action.run(); } catch (RuntimeException expected) { checks++; return; }
        throw new AssertionError("Expected validation failure");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}
