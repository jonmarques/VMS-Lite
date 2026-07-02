package br.com.jonmarques.vmslite.service;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.net.URLEncoder;

public class OnvifDiscoveryService {

private static class PerfilInfo {
    final String token;
    final String nome;
    final int largura;

    PerfilInfo(String token, String nome, int largura) {
        this.token = token;
        this.nome = nome;
        this.largura = largura;
    }
}

public static void discoverDevices(Consumer<Map<String, String>> callback) {
    Thread discoveryThread = new Thread(() -> {
        System.out.println("Iniciando descoberta de dispositivos ONVIF (WS-Discovery)...");
        Map<String, String> dispositivosEncontrados = new HashMap<>();
        DatagramSocket socket = null;
        
        try {
            socket = new DatagramSocket();
            socket.setSoTimeout(4000); // Timeout de 4 segundos

            String probeRequest = "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                    "<s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\" xmlns:a=\"http://www.w3.org/2005/08/addressing\">" +
                    "<s:Header>" +
                    "<a:Action s:mustUnderstand=\"1\">http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</a:Action>" +
                    "<a:To s:mustUnderstand=\"1\">urn:schemas-xmlsoap.org:ws:2005/04/discovery</a:To>" +
                    "</s:Header>" +
                    "<s:Body>" +
                    "<Probe xmlns=\"http://schemas.xmlsoap.org/ws/2005/04/discovery\">" +
                    "<Types xmlns:dn=\"http://www.onvif.org/ver10/network/wsdl\">dn:NetworkVideoTransmitter</Types>" +
                    "</Probe>" +
                    "</s:Body>" +
                    "</s:Envelope>";

            byte[] sendData = probeRequest.getBytes(StandardCharsets.UTF_8);
            InetAddress multicastAddress = InetAddress.getByName("239.255.255.250");
            
            DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, multicastAddress, 3702);
            socket.send(sendPacket);

            byte[] recvBuf = new byte[8192];
            while (true) {
                try {
                    DatagramPacket receivePacket = new DatagramPacket(recvBuf, recvBuf.length);
                    socket.receive(receivePacket);
                    
                    String response = new String(receivePacket.getData(), 0, receivePacket.getLength(), StandardCharsets.UTF_8);
                    String deviceIp = receivePacket.getAddress().getHostAddress();
                    
                    if (!dispositivosEncontrados.containsKey(deviceIp)) {
                        System.out.println("Dispositivo ONVIF respondendo no IP: " + deviceIp);
                        
                        // Tenta extrair a URL de serviço XAddr do XML se disponível, senão usa o IP como valor padrão
                        String xaddr = extrairXAddr(response);
                        if (xaddr == null || xaddr.isBlank()) {
                            xaddr = "http://" + deviceIp + "/onvif/device_service";
                        }
                        
                        dispositivosEncontrados.put(deviceIp, xaddr);
                    }
                } catch (java.net.SocketTimeoutException e) {
                    System.out.println("Varredura de rede finalizada (Timeout alcançado).");
                    break;
                }
            }
        } catch (Exception e) {
            System.err.println("Erro na descoberta de dispositivos: " + e.getMessage());
        } finally {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
            // Retorna o Map via callback permitindo o uso de .keySet()
            if (callback != null) {
                callback.accept(dispositivosEncontrados);
            }
        }
    });

    discoveryThread.setDaemon(true); // Evita travar build do Maven
    discoveryThread.start();
}

/**
 * Método auxiliar para extrair o endereço de serviço (XAddr) de dentro da resposta ProbeMatch
 */
private static String extrairXAddr(String xml) {
    try {
        Pattern pattern = Pattern.compile("<[^:>]*:?XAddrs>([^<]+)</[^:>]*:?XAddrs>");
        Matcher matcher = pattern.matcher(xml);
        if (matcher.find()) {
            // Pega o primeiro endereço listado (caso venha mais de um separado por espaço)
            return matcher.group(1).trim().split("\\s+")[0];
        }
    } catch (Exception e) {
        // Ignora falhas de extração secundárias
    }
    return null;
}

public String obterUrlRtsp(String serviceUrl, String usuario, String senha, String modelo, String ipPadrao, boolean preferirSubstream) {
    if (serviceUrl != null && !serviceUrl.isBlank()) {
    	
        long offsetRelogio = calcularOffsetRelogio(serviceUrl);

        String cabecalhoSeguranca = criarCabecalhoSeguranca(usuario, senha, offsetRelogio);
        List<PerfilInfo> perfis = obterPerfis(serviceUrl, cabecalhoSeguranca);

        if (!perfis.isEmpty()) {
            String tokenEscolhido = escolherPerfil(perfis, preferirSubstream);

            String urlRtsp = obterUriStreamOnvif(serviceUrl, cabecalhoSeguranca, tokenEscolhido);
            if (urlRtsp != null && !urlRtsp.isBlank()) {
                // Adiciona credenciais se necessário
                if (!urlRtsp.contains("@") && usuario != null && !usuario.isBlank()) {
                    urlRtsp = urlRtsp.replace("rtsp://", "rtsp://" + usuario + ":" + senha + "@");
                }
                return urlRtsp;
            }
        }
    }

    System.out.println("ONVIF falhou ou indisponível. Fallback para: " + modelo);
    return montarUrlRtspFallback(ipPadrao, usuario, senha, modelo);
}

private String escolherPerfil(List<PerfilInfo> perfis, boolean preferirSubstream) {
    boolean temResolucaoParaTodos = perfis.size() > 1
            && perfis.stream().allMatch(p -> p.largura > 0);

    if (temResolucaoParaTodos) {
        PerfilInfo escolhido = preferirSubstream
                ? perfis.stream().min(Comparator.comparingInt(p -> p.largura)).orElse(perfis.get(0))
                : perfis.stream().max(Comparator.comparingInt(p -> p.largura)).orElse(perfis.get(0));
        return escolhido.token;
    }

    for (PerfilInfo p : perfis) {
        boolean ehSub = p.nome.contains("sub") || p.nome.contains("low")
                || p.nome.contains("second") || p.nome.contains("mobile");
        if (preferirSubstream && ehSub) return p.token;
        if (!preferirSubstream && !ehSub) return p.token;
    }

    return perfis.get(0).token;
}


private long calcularOffsetRelogio(String serviceUrl) {
    try {
        String body = "<tds:GetSystemDateAndTime xmlns:tds=\"http://www.onvif.org/ver10/device/wsdl\"/>";
        String response = enviarRequisicaoSoap(serviceUrl, "<s:Header/>", body);
        if (response == null || response.isBlank()) return 0;

        Pattern p = Pattern.compile(
            "<[^:>]*:?UTCDateTime>.*?" +
            "<[^:>]*:?Year>(\\d+)</[^:>]*:?Year>.*?" +
            "<[^:>]*:?Month>(\\d+)</[^:>]*:?Month>.*?" +
            "<[^:>]*:?Day>(\\d+)</[^:>]*:?Day>.*?" +
            "<[^:>]*:?Hour>(\\d+)</[^:>]*:?Hour>.*?" +
            "<[^:>]*:?Minute>(\\d+)</[^:>]*:?Minute>.*?" +
            "<[^:>]*:?Second>(\\d+)</[^:>]*:?Second>",
            Pattern.DOTALL
        );
        Matcher m = p.matcher(response);
        if (m.find()) {
            ZonedDateTime deviceTime = ZonedDateTime.of(
                Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)),
                Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5)), Integer.parseInt(m.group(6)),
                0, ZoneOffset.UTC
            );
            return deviceTime.toInstant().toEpochMilli() - Instant.now().toEpochMilli();
        }
    } catch (Exception e) {
        System.err.println("Aviso: não foi possível sincronizar relógio com o dispositivo ONVIF: " + e.getMessage());
    }
    return 0;
}

/**
 * 3. CRIAÇÃO DO CABEÇALHO DE SEGURANÇA
 */
private String criarCabecalhoSeguranca(String usuario, String senha, long offsetRelogioMs) {
    if (usuario == null || usuario.isBlank()) {
        return "<s:Header/>";
    }
    try {
        byte[] nonceBytes = new byte[16];
        new SecureRandom().nextBytes(nonceBytes);
        String nonceBase64 = Base64.getEncoder().encodeToString(nonceBytes);
        String created = Instant.now().plusMillis(offsetRelogioMs).toString();

        MessageDigest md = MessageDigest.getInstance("SHA-1");
        md.update(nonceBytes);
        md.update(created.getBytes(StandardCharsets.UTF_8));
        md.update(senha.getBytes(StandardCharsets.UTF_8));
        byte[] digestBytes = md.digest();
        String passwordDigest = Base64.getEncoder().encodeToString(digestBytes);

        return "<s:Header>" +
               "  <wsse:Security xmlns:wsse=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd\" " +
               "                 xmlns:wsu=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd\">" +
               "    <wsse:UsernameToken>" +
               "      <wsse:Username>" + usuario + "</wsse:Username>" +
               "      <wsse:Password Type=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordDigest\">" + passwordDigest + "</wsse:Password>" +
               "      <wsse:Nonce EncodingType=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary\">" + nonceBase64 + "</wsse:Nonce>" +
               "      <wsu:Created>" + created + "</wsu:Created>" +
               "    </wsse:UsernameToken>" +
               "  </wsse:Security>" +
               "</s:Header>";
    } catch (Exception e) {
        System.err.println("Erro ao gerar cabeçalho WS-Security: " + e.getMessage());
        return "<s:Header/>";
    }
}


private List<PerfilInfo> obterPerfis(String serviceUrl, String cabecalhoSeguranca) {
    // LinkedHashMap/List preserva a ordem em que os perfis vieram na resposta da
    // câmera (normalmente o stream principal é listado primeiro).
    List<PerfilInfo> perfis = new ArrayList<>();
    String response = enviarRequisicaoSoap(serviceUrl, cabecalhoSeguranca, "<trt:GetProfiles/>");
    if (response == null || response.isBlank()) return perfis;

    // Aceita "Profile" ou "Profiles" (varia por firmware/versão ONVIF) e qualquer
    // prefixo de namespace (ou nenhum), com atributos extras antes do ">".
    Pattern blocoPattern = Pattern.compile(
        "<[^:>]*:?Profiles?[^>]*\\btoken=\"([^\"]+)\"[^>]*>(.*?)</[^:>]*:?Profiles?>",
        Pattern.DOTALL
    );
    Matcher blocoMatcher = blocoPattern.matcher(response);

    while (blocoMatcher.find()) {
        String token = blocoMatcher.group(1);
        String bloco = blocoMatcher.group(2);

        String nome = "";
        Matcher nomeMatcher = Pattern.compile("<[^:>]*:?Name>([^<]+)</[^:>]*:?Name>").matcher(bloco);
        if (nomeMatcher.find()) {
            nome = nomeMatcher.group(1).trim().toLowerCase();
        }

        int largura = -1;
        Matcher largMatcher = Pattern.compile(
            "<[^:>]*:?Resolution>\\s*<[^:>]*:?Width>(\\d+)</[^:>]*:?Width>",
            Pattern.DOTALL
        ).matcher(bloco);
        if (largMatcher.find()) {
            try {
                largura = Integer.parseInt(largMatcher.group(1));
            } catch (NumberFormatException ignored) {}
        }

        perfis.add(new PerfilInfo(token, nome, largura));
    }
    return perfis;
}

/**
 * 5. CAPTURA DA URI DE STREAMING
 */
private String obterUriStreamOnvif(String serviceUrl, String cabecalhoSeguranca, String token) {
    String body = "<trt:GetStreamUri>" +
                  "  <trt:StreamSetup>" +
                  "    <tt:Stream>RTP-Unicast</tt:Stream>" +
                  "    <tt:Transport>" +
                  "      <tt:Protocol>RTSP</tt:Protocol>" +
                  "    </tt:Transport>" +
                  "  </trt:StreamSetup>" +
                  "  <trt:ProfileToken>" + token + "</trt:ProfileToken>" +
                  "</trt:GetStreamUri>";

    String response = enviarRequisicaoSoap(serviceUrl, cabecalhoSeguranca, body);
    if (response == null || response.isBlank()) {
        return null;
    }

    Pattern pattern = Pattern.compile("<[^:>]*:?Uri>([^<]+)</[^:>]*:?Uri>");
    Matcher matcher = pattern.matcher(response);
    if (matcher.find()) {
        return matcher.group(1).trim();
    }
    return null;
}

/**
 * 6. ENVIADOR DE REQUISIÇÕES SOAP GENÉRICO
 */
private String enviarRequisicaoSoap(String url, String header, String body) {
    try {
        URL obj = URI.create(url).toURL();
        HttpURLConnection con = (HttpURLConnection) obj.openConnection();
        con.setRequestMethod("POST");
        con.setRequestProperty("Content-Type", "application/soap+xml; charset=utf-8");
        con.setDoOutput(true);
        con.setConnectTimeout(5000);
        con.setReadTimeout(5000);

        String soapEnvelope = "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                "<s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\" " +
                "xmlns:trt=\"http://www.onvif.org/ver10/media/wsdl\" " +
                "xmlns:tt=\"http://www.onvif.org/ver10/schema\">" +
                header +
                "<s:Body>" +
                body +
                "</s:Body>" +
                "</s:Envelope>";

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
        System.err.println("Erro de conexão SOAP com o dispositivo: " + e.getMessage());
        return null;
    }
}

private String montarUrlRtspFallback(String ipPadrao, String usuario, String senha, String modelo) {
    if (ipPadrao == null || ipPadrao.isBlank()) return null;

    // 1. Tratar caracteres especiais na senha (evita erro na URL)
    String userEncoded = (usuario != null) ? URLEncoder.encode(usuario, StandardCharsets.UTF_8) : "";
    String passEncoded = (senha != null) ? URLEncoder.encode(senha, StandardCharsets.UTF_8) : "";
    String credenciais = (!userEncoded.isEmpty()) ? userEncoded + ":" + passEncoded + "@" : "";

    String mod = (modelo != null) ? modelo.toLowerCase() : "";

    // 2. Estrutura de Fallback (do mais específico para o mais genérico)
    
    // Hikvision / Intelbras (Série antiga)
    if (mod.contains("hikvision") || mod.contains("intelbras-old")) {
        return "rtsp://" + credenciais + ipPadrao + ":554/Streaming/Channels/101";
    } 
    
    // Dahua / Intelbras (Série nova/IP)
    else if (mod.contains("intelbras") || mod.contains("dahua")) {
        return "rtsp://" + credenciais + ipPadrao + ":554/cam/realmonitor?channel=1&subtype=0";
    }
    
    // TP-Link Tapo / VIGI
    else if (mod.contains("tapo") || mod.contains("vigi") || mod.contains("tp-link")) {
        return "rtsp://" + credenciais + ipPadrao + ":554/stream1";
    }
    
    // Reolink
    else if (mod.contains("reolink")) {
        return "rtsp://" + credenciais + ipPadrao + ":554/h264Preview_01_main";
    }
    
    // Axis
    else if (mod.contains("axis")) {
        return "rtsp://" + credenciais + ipPadrao + "/axis-media/media.amp?stream=1";
    }
    
    // Foscam
    else if (mod.contains("foscam")) {
        return "rtsp://" + credenciais + ipPadrao + ":88/videoMain";
    }

    // Geovision
    else if (mod.contains("geovision")) {
        return "rtsp://" + credenciais + ipPadrao + ":8554/ch01.264";
    }

    // 3. Fallbacks Genéricos (tentativa por tentativa)
    else {
        // Se não identificou a marca, tenta os caminhos padrão da indústria
        String[] tentativas = {
            "/onvif1", 
            "/live0", 
            "/ch01/0", 
            "/stream1", 
            "/live/ch1"
        };
        
        // Retorna o primeiro formato genérico (pode ser expandido conforme necessidade)
        return "rtsp://" + credenciais + ipPadrao + ":554" + tentativas[0];
    }
}
}