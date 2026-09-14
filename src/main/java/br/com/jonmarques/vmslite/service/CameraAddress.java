package br.com.jonmarques.vmslite.service;

import java.net.URI;

public final class CameraAddress {
    private CameraAddress() {}

    public static String host(String address) {
        try {
            return address == null ? null : URI.create(address).getHost();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static String replaceHost(String address, String newHost) {
        if (address == null || newHost == null) return address;
        try {
            URI uri = URI.create(address);
            if (uri.getHost() == null) return address;
            String host = newHost.contains(":") && !newHost.startsWith("[") ? "[" + newHost + "]" : newHost;
            String authority = (uri.getRawUserInfo() == null ? "" : uri.getRawUserInfo() + "@")
                    + host + (uri.getPort() < 0 ? "" : ":" + uri.getPort());
            return uri.getScheme() + "://" + authority
                    + (uri.getRawPath() == null ? "" : uri.getRawPath())
                    + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery())
                    + (uri.getRawFragment() == null ? "" : "#" + uri.getRawFragment());
        } catch (IllegalArgumentException e) {
            return address;
        }
    }
}
