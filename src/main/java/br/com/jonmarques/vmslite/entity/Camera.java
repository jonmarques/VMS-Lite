package br.com.jonmarques.vmslite.entity;
public class Camera {

    private String name;
    private String url;
    private int rowSpan;
    private int colSpan;
    
    public Camera() {
		// TODO Auto-generated constructor stub
	}
    
    public Camera(String name, String url, int rowSpan, int colSpan) {
        this.name = name;
        this.url = url;
        this.rowSpan = rowSpan;
        this.colSpan = colSpan;
    }

    public Camera(String name, String url) {
        this.name = name;
        this.url = url;
        this.rowSpan = 1;
        this.colSpan = 1;
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
    
    
    
}