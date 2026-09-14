package br.com.jonmarques.vmslite.service;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

final class OnvifSoapClient {
    private OnvifSoapClient() {}
    static String post(String url, String header, String body) {
        HttpURLConnection con = null;
        try {
            URL obj = URI.create(url).toURL();
            con = (HttpURLConnection) obj.openConnection();
            con.setRequestMethod("POST");
            con.setRequestProperty("Content-Type", "application/soap+xml; charset=utf-8");
            con.setDoOutput(true);
            con.setConnectTimeout(5000);
            con.setReadTimeout(5000);

            String soapEnvelope = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                    + "<s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\" "
                    + "xmlns:trt=\"http://www.onvif.org/ver10/media/wsdl\" "
                    + "xmlns:tt=\"http://www.onvif.org/ver10/schema\">"
                    + header
                    + "<s:Body>"
                    + body
                    + "</s:Body>"
                    + "</s:Envelope>";

            try (OutputStream os = con.getOutputStream()) {
                byte[] input = soapEnvelope.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            int responseCode = con.getResponseCode();
            InputStream is = (responseCode >= 200 && responseCode < 300) ? con.getInputStream() : con.getErrorStream();
            if (is == null) return null;

            try (BufferedReader in = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                StringBuilder response = new StringBuilder();
                String inputLine;
                while ((inputLine = in.readLine()) != null) {
                    response.append(inputLine);
                }
                return response.toString();
            }
        } catch (Exception e) {

            return null;
        } finally {
            if (con != null) con.disconnect();
        }
    }

}
