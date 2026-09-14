package br.com.jonmarques.vmslite.service;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.*;
import static br.com.jonmarques.vmslite.service.OnvifRequestException.Reason.*;

public final class OnvifSoapChecks {
    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            try {
                exchange.getRequestBody().readAllBytes();
                String path = exchange.getRequestURI().getPath();
                int code = path.equals("/unauthorized") ? 401 : path.equals("/missing") ? 404 : 200;
                String body = switch (path) {
                    case "/large" -> "<Envelope>" + "x".repeat(8192) + "</Envelope>";
                    case "/invalid" -> "not XML";
                    case "/fault" -> "<Envelope><Fault><Code><Value>ter:NotAuthorized</Value></Code></Fault></Envelope>";
                    default -> "<Envelope><Body>ok</Body></Envelope>";
                };
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(code, path.equals("/slow") ? 0 : bytes.length);
                if (path.equals("/slow")) {
                    for (int i = 0; i < 20; i++) {
                        exchange.getResponseBody().write('x'); exchange.getResponseBody().flush();
                        try { Thread.sleep(50); } catch (InterruptedException error) { Thread.currentThread().interrupt(); break; }
                    }
                } else exchange.getResponseBody().write(bytes);
            } catch (java.io.IOException disconnected) { }
            finally { exchange.close(); }
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        try {
            if (!post(base + "/ok", 2000).contains("ok")) throw new AssertionError("Valid SOAP response");
            expect(AUTHENTICATION, () -> post(base + "/unauthorized", 2000));
            expect(AUTHENTICATION, () -> post(base + "/fault", 2000));
            expect(ENDPOINT, () -> post(base + "/missing", 2000));
            expect(RESPONSE, () -> post(base + "/invalid", 2000));
            expect(SIZE, () -> post(base + "/large", 2000));
            long start = System.nanoTime();
            expect(TIMEOUT, () -> post(base + "/slow", 200));
            if (TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) > 1500) throw new AssertionError("Deadline not enforced on streaming body");
            expect(ADDRESS, () -> post("http://user:secret@127.0.0.1/", 2000));
            var service = new OnvifMediaService();
            var fallback = service.resolveStream(base + "/missing", "user", "secret", "hikvision", "127.0.0.1", true);
            if (fallback.warning() == null || !fallback.url().endsWith("/102")) throw new AssertionError("Preserve fallback with safe diagnostic");
            expect(AUTHENTICATION, () -> service.resolveStream(base + "/unauthorized", "user", "secret", "hikvision", "127.0.0.1", true));
            System.out.println("OK: HTTP/SOAP diagnostics, bounded body, streaming deadline, redaction, fallback compatibility");
        } finally { server.stop(0); executor.shutdownNow(); executor.awaitTermination(3, TimeUnit.SECONDS); }
    }
    private static String post(String url, long millis) {
        return OnvifSoapClient.post(url, "<s:Header/>", "<trt:GetProfiles/>", Duration.ofMillis(millis), 4096);
    }
    private static void expect(OnvifRequestException.Reason reason, Runnable action) {
        try { action.run(); } catch (OnvifRequestException error) {
            if (error.getReason() != reason) throw new AssertionError(error);
            if (error.getMessage().contains("secret") || error.getMessage().contains("http://")) throw new AssertionError("Sensitive diagnostic");
            return;
        }
        throw new AssertionError("Expected " + reason);
    }
}
