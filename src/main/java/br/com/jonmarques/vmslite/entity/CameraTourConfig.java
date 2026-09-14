package br.com.jonmarques.vmslite.entity;

import java.util.ArrayList;
import java.util.List;

public final class CameraTourConfig {
    public static final int MIN_INTERVAL_SECONDS = 5;
    public static final int MAX_INTERVAL_SECONDS = 3600;
    private boolean enabled;
    private int intervalSeconds = 30;
    private List<String> cameraIds = new ArrayList<>();

    public CameraTourConfig() {}
    public CameraTourConfig(boolean enabled, int intervalSeconds, List<String> cameraIds) {
        this.enabled = enabled;
        this.intervalSeconds = intervalSeconds;
        this.cameraIds = new ArrayList<>(cameraIds);
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getIntervalSeconds() { return intervalSeconds; }
    public void setIntervalSeconds(int seconds) { intervalSeconds = seconds; }
    public List<String> getCameraIds() { return cameraIds; }
    public void setCameraIds(List<String> ids) { cameraIds = ids; }
    public CameraTourConfig copy() { return new CameraTourConfig(enabled, intervalSeconds, cameraIds); }
}
