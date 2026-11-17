package software.ulpgc.chess.backend.infrastructure.adapters;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import software.ulpgc.chess.backend.infrastructure.http.HttpResponses;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;

public final class GameServiceProxy implements HttpHandler, AutoCloseable {
    private final URI service;
    private final HttpClient client;

    public GameServiceProxy(URI service) {
        if (service == null || !Set.of("http", "https").contains(service.getScheme()) || service.getHost() == null
                || service.getRawUserInfo() != null || service.getRawQuery() != null || service.getRawFragment() != null
                || !Set.of("", "/").contains(service.getPath()))
            throw new IllegalArgumentException("Game service must be an HTTP origin.");
        this.service = service;
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    @Override public void handle(HttpExchange exchange) throws IOException {
        try {
            byte[] body = exchange.getRequestBody().readNBytes(8193);
            if (body.length > 8192) {
                HttpResponses.json(exchange, 413, "{\"message\":\"Request too large\"}");
                return;
            }
            String path = exchange.getRequestURI().getRawPath();
            String query = exchange.getRequestURI().getRawQuery();
            URI destination = URI.create(service.getScheme() + "://" + service.getRawAuthority()
                    + path + (query == null ? "" : "?" + query));
            var request = HttpRequest.newBuilder(destination).timeout(Duration.ofSeconds(8))
                    .method(exchange.getRequestMethod(), HttpRequest.BodyPublishers.ofByteArray(body));
            for (String name : Set.of("Cookie", "Content-Type", "Accept")) {
                String value = exchange.getRequestHeaders().getFirst(name);
                if (value != null) request.header(name, value);
            }
            HttpResponse<byte[]> response;
            try {
                response = client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
            } catch (IOException exception) {
                unavailable(exchange);
                return;
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                unavailable(exchange);
                return;
            }
            for (String name : Set.of("Set-Cookie", "Allow")) {
                for (String value : response.headers().allValues(name)) exchange.getResponseHeaders().add(name, value);
            }
            String type = response.headers().firstValue("Content-Type").orElse("application/json").split(";", 2)[0];
            HttpResponses.send(exchange, response.statusCode(), type, response.body());
        } finally {
            exchange.close();
        }
    }

    private void unavailable(HttpExchange exchange) throws IOException {
        HttpResponses.json(exchange, 503, "{\"message\":\"Game service is unavailable. Try again shortly.\"}");
    }

    @Override public void close() { client.close(); }
}
