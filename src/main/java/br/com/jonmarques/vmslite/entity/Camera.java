package br.com.jonmarques.vmslite.entity;
public class Camera {

    private String id = java.util.UUID.randomUUID().toString();
    private volatile String name;
    private volatile String url;
    private int rowSpan = 1;
    private int colSpan = 1;
    private volatile String uuid;

    public Camera() {}

    public Camera(String name, String url, String uuid, int rowSpan, int colSpan) {
        this.name = name;
        this.url = url;
        this.uuid = uuid;
        this.rowSpan = rowSpan;
        this.colSpan = colSpan;
    }

    public Camera(String name, String url, String uuid) {
        this(name, url, uuid, 1, 1);
    }

    public String getId() { return id; }
    public void setId(String id) {
        this.id = id == null || id.isBlank() ? java.util.UUID.randomUUID().toString() : id;
    }

    public String getName() {
        return name;
    }

    public String getUrl() {
        return url;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public int getRowSpan() {
        return rowSpan;
    }

    public void setRowSpan(int rowSpan) {
        this.rowSpan = rowSpan;
    }

    public int getColSpan() {
        return colSpan;
    }

    public void setColSpan(int colSpan) {
        this.colSpan = colSpan;
    }

    public String getUuid() {
        return uuid;
    }

    public void setUuid(String uuid) {
        this.uuid = uuid;
    }
}
