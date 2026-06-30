package br.com.jonmarques.vmslite.entity;

import java.util.List;

public class VMSConfig {

    private int layoutCols;
    private int layoutRows;
    private List<Camera> cameras;

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
}