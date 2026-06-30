package br.com.jonmarques.vmslite.service;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.net.URLEncoder;

public class OnvifDiscoveryService {

    /**
     * 1. MÉTODO DE DESCOBERTA (WS-Discovery) - AJUSTADO PARA RETORNAR MAP
     * Alinhado com o seu código principal que faz uso de .keySet()
     */
    public static void discoverDevices(Consumer<Map<String, String>> callback) {
        Thread discoveryThread = new Thread(() -> {
            System.out.println("Iniciando descoberta de dispositivos ONVIF (WS-Discovery)...");
            Map<String, String> dispositivosEncontrados = new HashMap<>();
            DatagramSocket socket = null;
            
            try {
                socket = new DatagramSocket();
                socket.setSoTimeout(4000); // Timeout de 4 segundos

                String uuid = UUID.randomUUID().toString();
                String probeRequest = "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                        "<s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\" xmlns:a=\"http://www.w3.org/2005/08/addressing\">" +
                        "<s:Header>" +
                        "<a:Action s:mustUnderstand=\"1\">http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</a:Action>" +
                        "<a:MessageID>urn:uuid:" + uuid + "</a:MessageID>" +
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
            String cabecalhoSeguranca = criarCabecalhoSeguranca(usuario, senha);
            Map<String, String> perfis = obterTokensPerfis(serviceUrl, cabecalhoSeguranca);

            if (!perfis.isEmpty()) {
                String tokenEscolhido = null;

                // Lógica de seleção inteligente
                for (Map.Entry<String, String> entry : perfis.entrySet()) {
                    String nome = entry.getValue();
                    boolean ehSub = nome.contains("sub") || nome.contains("low") || nome.contains("second");
                    
                    if (preferirSubstream && ehSub) {
                        tokenEscolhido = entry.getKey();
                        break; 
                    } else if (!preferirSubstream && !ehSub) {
                        tokenEscolhido = entry.getKey();
                        break;
                    }
                }
                
                // Se não achou o desejado, pega o primeiro disponível
                if (tokenEscolhido == null) tokenEscolhido = perfis.keySet().iterator().next();

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
    /**
     * 3. CRIAÇÃO DO CABEÇALHO DE SEGURANÇA
     */
    private String criarCabecalhoSeguranca(String usuario, String senha) {
        if (usuario == null || usuario.isBlank()) {
            return "<s:Header/>";
        }
        try {
            byte[] nonceBytes = new byte[16];
            new SecureRandom().nextBytes(nonceBytes);
            String nonceBase64 = Base64.getEncoder().encodeToString(nonceBytes);
            String created = Instant.now().toString();

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

    /**
     * Retorna um mapa de Token -> Nome do Perfil
     */
    private Map<String, String> obterTokensPerfis(String serviceUrl, String cabecalhoSeguranca) {
        Map<String, String> perfis = new HashMap<>();
        String response = enviarRequisicaoSoap(serviceUrl, cabecalhoSeguranca, "<trt:GetProfiles/>");
        if (response == null || response.isBlank()) return perfis;

        // Regex que captura tanto o token quanto o nome do perfil
        Pattern pattern = Pattern.compile("<tt:Profile token=\"([^\"]+)\">.*?<tt:Name>([^<]+)</tt:Name>", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(response);
        while (matcher.find()) {
            String token = matcher.group(1);
            String nome = matcher.group(2).toLowerCase();
            perfis.put(token, nome);
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
            URL obj = new URL(url);
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

;

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