package br.com.jonmarques.vmslite.playback;

import br.com.jonmarques.vmslite.entity.Camera;
import br.com.jonmarques.vmslite.entity.CameraTourConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import javax.swing.Timer;

/** All calls run on Swing's event thread; no playback or native resources are owned here. */
public final class CameraTourController implements AutoCloseable {
    private final Consumer<Camera> display;
    private final Timer timer;
    private List<Camera> sequence = List.of();
    private Camera current;
    private String currentUrl;
    private String currentName;
    private boolean paused;

    public CameraTourController(Consumer<Camera> display) {
        this.display = display;
        timer = new Timer(30_000, event -> advance());
    }

    public void configure(CameraTourConfig config, List<Camera> available) {
        List<Camera> selected = new ArrayList<>();
        if (config.isEnabled()) {
            for (String id : config.getCameraIds()) {
                available.stream().filter(camera -> camera.getId().equals(id)).findFirst().ifPresent(selected::add);
            }
        }
        sequence = selected;
        int delay = config.getIntervalSeconds() * 1000;
        if (timer.getDelay() != delay) {
            timer.setDelay(delay);
            timer.setInitialDelay(delay);
            if (timer.isRunning()) timer.restart();
        }
        Camera next = sequence.stream().filter(camera -> current != null
                && camera.getId().equals(current.getId())).findFirst().orElse(sequence.isEmpty() ? null : sequence.get(0));
        show(next);
        updateTimer();
    }

    public void advance() {
        if (sequence.isEmpty()) return;
        int index = -1;
        for (int i = 0; i < sequence.size(); i++) {
            if (current != null && sequence.get(i).getId().equals(current.getId())) index = i;
        }
        show(sequence.get((index + 1) % sequence.size()));
    }

    public void next() {
        advance();
        if (timer.isRunning()) timer.restart();
    }

    private void show(Camera camera) {
        boolean changed = current != camera || !Objects.equals(currentUrl, camera == null ? null : camera.getUrl())
                || !Objects.equals(currentName, camera == null ? null : camera.getName());
        current = camera;
        currentUrl = camera == null ? null : camera.getUrl();
        currentName = camera == null ? null : camera.getName();
        if (changed) display.accept(camera);
    }

    public void setPaused(boolean paused) { this.paused = paused; updateTimer(); }
    public boolean isPaused() { return paused; }
    public boolean isRunning() { return timer.isRunning(); }
    public Camera getCurrent() { return current; }

    private void updateTimer() {
        if (paused || sequence.size() < 2) timer.stop();
        else if (!timer.isRunning()) timer.start();
    }

    @Override public void close() {
        timer.stop();
        sequence = List.of();
        current = null;
        currentUrl = null;
        currentName = null;
    }
}
