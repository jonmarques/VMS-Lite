package br.com.jonmarques.vmslite.playback;

import br.com.jonmarques.vmslite.entity.*;
import br.com.jonmarques.vmslite.service.ConfigService;
import br.com.jonmarques.vmslite.ui.CameraGridLayout;
import java.awt.Rectangle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.swing.*;

public final class CameraTourChecks {
    private static int checks;
    public static void main(String[] args) throws Exception {
        SwingUtilities.invokeAndWait(CameraTourChecks::sequence);
        automaticAdvance();
        configuration();
        grid();
        System.out.println("OK: " + checks + " camera tour checks");
    }

    private static Camera camera(String name) { return new Camera(name, "rtsp://host/" + name, null, 1, 1); }

    private static void automaticAdvance() throws Exception {
        Camera a = camera("a"), b = camera("b");
        var switched = new java.util.concurrent.CountDownLatch(1);
        CameraTourController[] controller = new CameraTourController[1];
        SwingUtilities.invokeAndWait(() -> {
            controller[0] = new CameraTourController(camera -> { if (camera == b) switched.countDown(); });
            controller[0].configure(new CameraTourConfig(true, 5, List.of(a.getId(), b.getId())), List.of(a, b));
        });
        try { check(switched.await(8, java.util.concurrent.TimeUnit.SECONDS), "Automatic timed advance"); }
        finally { SwingUtilities.invokeAndWait(controller[0]::close); }
    }

    private static void sequence() {
        Camera a = camera("a"), b = camera("b"), c = camera("c");
        var selected = new CameraTourConfig(true, 30, List.of(b.getId(), a.getId()));
        List<Camera> displayed = new ArrayList<>();
        try (var tour = new CameraTourController(displayed::add)) {
            tour.configure(selected, List.of(a, b, c));
            check(tour.getCurrent() == b && tour.isRunning(), "Selected order and timer");
            tour.configure(selected, List.of(a, b, c));
            check(displayed.size() == 1, "Saving unchanged config does not reload");
            tour.next();
            check(tour.getCurrent() == a, "Next selected camera");
            tour.next();
            check(tour.getCurrent() == b, "Wrap around without unselected camera");
            tour.setPaused(true);
            check(!tour.isRunning(), "Pause stops timer");
            tour.next();
            check(tour.getCurrent() == a && !tour.isRunning(), "Manual next while paused");
            a.setUrl("rtsp://other/live");
            int count = displayed.size();
            tour.configure(selected, List.of(a, b, c));
            check(displayed.size() == count + 1, "Refresh edited URL");
            tour.setPaused(false);
            check(tour.isRunning(), "Resume");
            tour.configure(new CameraTourConfig(true, 5, List.of(b.getId())), List.of(b));
            check(tour.getCurrent() == b && !tour.isRunning(), "Removing active camera; single-camera timer off");
            tour.configure(new CameraTourConfig(), List.of(a, b));
            check(tour.getCurrent() == null && !tour.isRunning(), "Disable clears tour");
        }
    }

    private static void configuration() throws Exception {
        Path file = Files.createTempFile(Path.of("target"), "tour-check-", ".json");
        try {
            Camera a = camera("a"), b = camera("b");
            VMSConfig config = new VMSConfig(2, 3, new ArrayList<>(List.of(a, b)));
            config.setCameraTour(new CameraTourConfig(true, 60, List.of(b.getId(), a.getId())));
            VMSConfig snapshot = ConfigService.snapshot(config);
            config.getCameraTour().getCameraIds().clear();
            check(snapshot.getCameraTour().getCameraIds().size() == 2, "Deep tour snapshot");
            ConfigService.saveToFile(snapshot, file.toFile());
            var restored = ConfigService.loadFromFile(file.toFile());
            check(restored.getCameras().get(0).getId().equals(a.getId()), "Stable IDs persisted");
            check(restored.getCameraTour().isEnabled() && restored.getCameraTour().getIntervalSeconds() == 60
                    && restored.getCameraTour().getCameraIds().equals(List.of(b.getId(), a.getId())), "Tour roundtrip");
            restored.getCameraTour().setCameraIds(List.of("missing"));
            boolean rejected = false;
            try { ConfigService.snapshot(restored); } catch (IllegalArgumentException expected) { rejected = true; }
            check(rejected, "Reject unknown selected camera");
            Files.writeString(file, "{\"cameras\":[{\"name\":\"old\",\"url\":\"rtsp://host/live\"}]}");
            restored = ConfigService.loadFromFile(file.toFile());
            check(!restored.getCameraTour().isEnabled() && restored.getCameras().get(0).getId() != null,
                    "Legacy config defaults to disabled tour and receives IDs");
        } finally { Files.deleteIfExists(file); }
    }

    private static void grid() {
        for (int columns = 1; columns <= 20; columns++) {
            var positions = CameraGridLayout.positions(List.of(camera("a"), camera("b")), columns, true);
            check(positions.get(0).equals(new Rectangle(0, 0, 2, 2)), "Fixed 2x2 top-left tour");
            for (int i = 0; i < positions.size(); i++) {
                check(positions.get(i).x + positions.get(i).width <= Math.max(2, columns), "Within columns");
                for (int j = 0; j < i; j++) check(!positions.get(i).intersects(positions.get(j)), "No overlap");
            }
        }
        JPanel container = new JPanel(null), tour = new JPanel();
        container.setSize(640, 480);
        container.add(tour);
        CameraGridLayout.apply(container, List.of(), tour, 1, 1);
        check(tour.getBounds().equals(new Rectangle(0, 0, 640, 480)), "Tour-only fills minimum grid");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}
