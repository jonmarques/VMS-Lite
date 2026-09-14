package br.com.jonmarques.vmslite.service;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;

public final class OnvifMediaService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final boolean DEBUG = Boolean.getBoolean("vmslite.debug");
    private static class PerfilInfo {
        String token;
        String nome;
        String encoding;
        int largura;

        PerfilInfo(String token, String nome, String encoding, int largura) {
            this.token = token;
            this.nome = nome;
            this.encoding = encoding;
            this.largura = largura;
        }
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
                    if (!urlRtsp.contains("@") && usuario != null && !usuario.isBlank()) {

                        String usuarioUrl = URLEncoder.encode(usuario, StandardCharsets.UTF_8)
                                .replace("+", "%20");

                        String senhaUrl = URLEncoder.encode(senha == null ? "" : senha, StandardCharsets.UTF_8)
                                .replace("+", "%20");

                        urlRtsp = urlRtsp.replace(
                            "rtsp://",
                            "rtsp://" + usuarioUrl + ":" + senhaUrl + "@"
                        );
                    }
                    return urlRtsp;
                }
            }
        }

        logDebug("ONVIF falhou ou indisponivel. Fallback para: " + modelo);
        return montarUrlRtspFallback(ipPadrao, usuario, senha, modelo, preferirSubstream);
    }

    private String escolherPerfil(List<PerfilInfo> perfis, boolean preferirSubstream) {

        if (perfis.isEmpty()) {
            return null;
        }

        // Usa apenas streams H264/H265 para vídeo
        List<PerfilInfo> perfisVideo = perfis.stream()
                .filter(p -> "H264".equalsIgnoreCase(p.encoding)
                          || "H265".equalsIgnoreCase(p.encoding))
                .toList();

        if (!perfisVideo.isEmpty()) {
            perfis = perfisVideo;
        }

        // Primeiro tenta identificar pelo nome
        for (PerfilInfo p : perfis) {

            boolean ehSub =
                    p.nome.contains("sub") ||
                    p.nome.contains("minor") ||
                    p.nome.contains("low") ||
                    p.nome.contains("second") ||
                    p.nome.contains("secondary") ||
                    p.nome.contains("mobile");

            if (preferirSubstream && ehSub)
                return p.token;

            if (!preferirSubstream && !ehSub)
                return p.token;
        }

        // Se não encontrou pelo nome, usa resolução
        boolean temResolucaoParaTodos =
                perfis.size() > 1 &&
                perfis.stream().allMatch(p -> p.largura > 0);

        if (temResolucaoParaTodos) {

            PerfilInfo escolhido = preferirSubstream
                    ? perfis.stream()
                            .min(Comparator.comparingInt(p -> p.largura))
                            .orElse(perfis.get(0))
                    : perfis.stream()
                            .max(Comparator.comparingInt(p -> p.largura))
                            .orElse(perfis.get(0));

            return escolhido.token;
        }

        return perfis.get(0).token;
    }

    private long calcularOffsetRelogio(String serviceUrl) {
        try {
            String response = enviarRequisicaoSoap(serviceUrl, "<s:Header/>",
                    "<tds:GetSystemDateAndTime xmlns:tds=\"http://www.onvif.org/ver10/device/wsdl\"/>");
            var root = OnvifXml.parse(response);
            var dates = OnvifXml.elements(root, "UTCDateTime");
            if (dates.isEmpty()) return 0;
            var date = dates.get(0);
            ZonedDateTime deviceTime = ZonedDateTime.of(
                    Integer.parseInt(OnvifXml.text(date, "Year")),
                    Integer.parseInt(OnvifXml.text(date, "Month")),
                    Integer.parseInt(OnvifXml.text(date, "Day")),
                    Integer.parseInt(OnvifXml.text(date, "Hour")),
                    Integer.parseInt(OnvifXml.text(date, "Minute")),
                    Integer.parseInt(OnvifXml.text(date, "Second")), 0, ZoneOffset.UTC);
            return deviceTime.toInstant().toEpochMilli() - Instant.now().toEpochMilli();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private String criarCabecalhoSeguranca(String usuario, String senha, long offsetRelogioMs) {
        if (usuario == null || usuario.isBlank()) {
            return "<s:Header/>";
        }
        try {
            byte[] nonceBytes = new byte[16];
            RANDOM.nextBytes(nonceBytes);
            String nonceBase64 = Base64.getEncoder().encodeToString(nonceBytes);
            String created = Instant.now().plusMillis(offsetRelogioMs).toString();

            MessageDigest md = MessageDigest.getInstance("SHA-1");
            md.update(nonceBytes);
            md.update(created.getBytes(StandardCharsets.UTF_8));
            md.update((senha == null ? "" : senha).getBytes(StandardCharsets.UTF_8));
            byte[] digestBytes = md.digest();
            String passwordDigest = Base64.getEncoder().encodeToString(digestBytes);

            return "<s:Header>"
                    + "  <wsse:Security xmlns:wsse=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd\" "
                    + "                 xmlns:wsu=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd\">"
                    + "    <wsse:UsernameToken>"
                    + "      <wsse:Username>" + OnvifXml.escape(usuario) + "</wsse:Username>"
                    + "      <wsse:Password Type=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordDigest\">" + passwordDigest + "</wsse:Password>"
                    + "      <wsse:Nonce EncodingType=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary\">" + nonceBase64 + "</wsse:Nonce>"
                    + "      <wsu:Created>" + created + "</wsu:Created>"
                    + "    </wsse:UsernameToken>"
                    + "  </wsse:Security>"
                    + "</s:Header>";
        } catch (Exception e) {
            System.err.println("Erro ao gerar cabecalho WS-Security: " + e.getMessage());
            return "<s:Header/>";
        }
    }

    private List<PerfilInfo> obterPerfis(String serviceUrl, String header) {
        List<PerfilInfo> profiles = new ArrayList<>();
        var root = OnvifXml.parse(enviarRequisicaoSoap(serviceUrl, header, "<trt:GetProfiles/>"));
        for (var element : OnvifXml.elements(root, "Profiles")) {
            String token = element.getAttribute("token");
            if (token.isBlank()) continue;
            String name = OnvifXml.text(element, "Name");
            String encoding = OnvifXml.text(element, "Encoding");
            int width = -1;
            try { width = Integer.parseInt(OnvifXml.text(element, "Width")); }
            catch (NumberFormatException ignored) { }
            profiles.add(new PerfilInfo(token, name == null ? "" : name.toLowerCase(java.util.Locale.ROOT),
                    encoding == null ? "" : encoding, width));
        }
        return profiles;
    }

    private String obterUriStreamOnvif(String serviceUrl, String cabecalhoSeguranca, String token) {
        String body = "<trt:GetStreamUri>"
                + "  <trt:StreamSetup>"
                + "    <tt:Stream>RTP-Unicast</tt:Stream>"
                + "    <tt:Transport>"
                + "      <tt:Protocol>RTSP</tt:Protocol>"
                + "    </tt:Transport>"
                + "  </trt:StreamSetup>"
                + "  <trt:ProfileToken>" + OnvifXml.escape(token) + "</trt:ProfileToken>"
                + "</trt:GetStreamUri>";

        String response = enviarRequisicaoSoap(serviceUrl, cabecalhoSeguranca, body);
        if (response == null || response.isBlank()) {
            return null;
        }

        return OnvifXml.text(OnvifXml.parse(response), "Uri");
    }

    private String enviarRequisicaoSoap(String url, String header, String body) {
        return OnvifSoapClient.post(url, header, body);
    }

    private String montarUrlRtspFallback(String ipPadrao, String usuario, String senha, String modelo, boolean substream) {
        if (ipPadrao == null || ipPadrao.isBlank()) return null;

        String userEncoded = encodeRtspCredential(usuario);
        String passEncoded = encodeRtspCredential(senha);
        String credenciais = (!userEncoded.isEmpty()) ? userEncoded + ":" + passEncoded + "@" : "";

        String mod = (modelo != null) ? modelo.toLowerCase() : "";

        if (mod.contains("hikvision") || mod.contains("intelbras-old")) {
            return "rtsp://" + credenciais + ipPadrao + ":554/Streaming/Channels/" + (substream ? "102" : "101");
        } else if (mod.contains("intelbras") || mod.contains("dahua")) {
            return "rtsp://" + credenciais + ipPadrao + ":554/cam/realmonitor?channel=1&subtype=" + (substream ? "1" : "0");
        } else if (mod.contains("tapo") || mod.contains("vigi") || mod.contains("tp-link")) {
            return "rtsp://" + credenciais + ipPadrao + ":554/stream" + (substream ? "2" : "1");
        } else if (mod.contains("reolink")) {
            return "rtsp://" + credenciais + ipPadrao + ":554/h264Preview_01_" + (substream ? "sub" : "main");
        } else if (mod.contains("axis")) {
            return "rtsp://" + credenciais + ipPadrao + "/axis-media/media.amp?stream=1";
        } else if (mod.contains("foscam")) {
            return "rtsp://" + credenciais + ipPadrao + ":88/video" + (substream ? "Sub" : "Main");
        } else if (mod.contains("geovision")) {
            return "rtsp://" + credenciais + ipPadrao + ":8554/ch01.264";
        } else {
            return "rtsp://" + credenciais + ipPadrao + ":554/onvif1";
        }
    }

    private static String encodeRtspCredential(String valor) {
        if (valor == null) return "";
        return URLEncoder.encode(valor, StandardCharsets.UTF_8)
                .replace("+", "%20");
    }

    private static void logDebug(String message) { if (DEBUG) System.out.println(message); }
}
