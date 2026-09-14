package br.com.jonmarques.vmslite.service;

/** User-safe diagnostics: never carry URLs, credentials or device-supplied fault text. */
public final class OnvifRequestException extends RuntimeException {
    public enum Reason { ADDRESS, TIMEOUT, SIZE, AUTHENTICATION, ENDPOINT, HTTP, SOAP, RESPONSE, NETWORK }
    private final Reason reason;
    public OnvifRequestException(Reason reason) {
        super(switch (reason) {
            case ADDRESS -> "Endereco do servico ONVIF invalido.";
            case TIMEOUT -> "A camera excedeu o prazo da requisicao ONVIF.";
            case SIZE -> "A resposta ONVIF excedeu o limite de tamanho permitido.";
            case AUTHENTICATION -> "A camera recusou a autenticacao ONVIF. Verifique usuario, senha e permissoes.";
            case ENDPOINT -> "O endereco ou a operacao do servico ONVIF nao esta disponivel.";
            case HTTP -> "A camera retornou um erro HTTP na requisicao ONVIF.";
            case SOAP -> "A camera recusou a operacao ONVIF.";
            case RESPONSE -> "A camera retornou uma resposta ONVIF invalida.";
            case NETWORK -> "Nao foi possivel comunicar com o servico ONVIF da camera.";
        });
        this.reason = reason;
    }
    public Reason getReason() { return reason; }
}
