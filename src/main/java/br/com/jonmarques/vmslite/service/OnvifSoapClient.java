package br.com.jonmarques.vmslite.service;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import static br.com.jonmarques.vmslite.service.OnvifRequestException.Reason.*;

final class OnvifSoapClient {
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).version(HttpClient.Version.HTTP_1_1).build();
    private OnvifSoapClient() {}

    static String post(String url, String header, String body) {
        return post(url, header, body, Duration.ofSeconds(10), MAX_BYTES);
    }

    static String post(String url, String header, String body, Duration timeout, int maximumBytes) {
        String envelope = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\" "
                + "xmlns:trt=\"http://www.onvif.org/ver10/media/wsdl\" xmlns:tt=\"http://www.onvif.org/ver10/schema\">"
                + header + "<s:Body>" + body + "</s:Body></s:Envelope>";
        HttpRequest request;
        try {
            URI address = URI.create(url);
            if (address.getHost() == null || address.getUserInfo() != null
                    || !("http".equalsIgnoreCase(address.getScheme()) || "https".equalsIgnoreCase(address.getScheme())))
                throw new IllegalArgumentException();
            request = HttpRequest.newBuilder(address).timeout(timeout)
                    .header("Content-Type", "application/soap+xml; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(envelope, StandardCharsets.UTF_8)).build();
        } catch (IllegalArgumentException | NullPointerException error) { throw new OnvifRequestException(ADDRESS); }
        CompletableFuture<HttpResponse<byte[]>> pending = CLIENT.sendAsync(request, info -> new LimitedBody(maximumBytes));
        try {
            HttpResponse<byte[]> response = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            int status = response.statusCode();
            if (status == 401 || status == 403) throw new OnvifRequestException(AUTHENTICATION);
            if (status == 404 || status == 405) throw new OnvifRequestException(ENDPOINT);
            String text = new String(response.body(), StandardCharsets.UTF_8);
            var xml = OnvifXml.parse(text);
            if (!OnvifXml.elements(xml, "Fault").isEmpty()) {
                for (var code : OnvifXml.elements(xml, "Value")) {
                    String value = code.getTextContent().trim();
                    if (value.endsWith(":NotAuthorized") || value.endsWith(":FailedAuthentication"))
                        throw new OnvifRequestException(AUTHENTICATION);
                    if (value.endsWith(":ActionNotSupported")) throw new OnvifRequestException(ENDPOINT);
                }
                throw new OnvifRequestException(SOAP);
            }
            if (status < 200 || status >= 300) throw new OnvifRequestException(HTTP);
            if (xml == null || !"Envelope".equals(xml.getLocalName())) throw new OnvifRequestException(RESPONSE);
            return text;
        } catch (TimeoutException error) {
            throw new OnvifRequestException(TIMEOUT);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new OnvifRequestException(NETWORK);
        } catch (ExecutionException error) {
            Throwable cause = error.getCause();
            while (cause != null) {
                if (cause instanceof OnvifRequestException safe) throw safe;
                if (cause instanceof HttpTimeoutException) throw new OnvifRequestException(TIMEOUT);
                cause = cause.getCause();
            }
            throw new OnvifRequestException(NETWORK);
        } finally { if (!pending.isDone()) pending.cancel(true); }
    }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final int maximum;
        private final ByteArrayOutputStream data = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private Flow.Subscription subscription;
        LimitedBody(int maximum) { this.maximum = maximum; }
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(1);
        }
        @Override public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if ((long) data.size() + buffer.remaining() > maximum) {
                    subscription.cancel();
                    result.completeExceptionally(new OnvifRequestException(SIZE));
                    return;
                }
                byte[] bytes = new byte[buffer.remaining()];
                buffer.get(bytes);
                data.writeBytes(bytes);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable error) { result.completeExceptionally(error); }
        @Override public void onComplete() { result.complete(data.toByteArray()); }
    }
}
