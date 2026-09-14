package br.com.jonmarques.vmslite.entity;

import java.util.List;
import java.util.ArrayList;

public class VMSConfig {

    private int layoutCols = 2;
    private int layoutRows = 2;
    private List<Camera> cameras = new ArrayList<>();
    private CameraTourConfig cameraTour = new CameraTourConfig();

    public VMSConfig() {}

    public VMSConfig(int layoutCols, int layoutRows, List<Camera> cameras) {
        this.layoutCols = layoutCols;
        this.layoutRows = layoutRows;
        this.cameras = cameras;
    }

    public int getLayoutCols() { return layoutCols; }
    public void setLayoutCols(int layoutCols) { this.layoutCols = layoutCols; }

    public int getLayoutRows() { return layoutRows; }
    public void setLayoutRows(int layoutRows) { this.layoutRows = layoutRows; }

    public List<Camera> getCameras() { return cameras; }
    public void setCameras(List<Camera> cameras) { this.cameras = cameras; }
    public CameraTourConfig getCameraTour() { return cameraTour; }
    public void setCameraTour(CameraTourConfig cameraTour) { this.cameraTour = cameraTour; }
}
