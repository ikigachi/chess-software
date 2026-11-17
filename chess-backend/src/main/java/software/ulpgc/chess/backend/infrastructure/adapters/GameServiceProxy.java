package software.ulpgc.chess.backend.infrastructure.adapters;

import io.javalin.http.Context;
import io.javalin.http.Handler;
import software.ulpgc.chess.backend.infrastructure.http.HttpResponses;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;

public final class GameServiceProxy implements Handler, AutoCloseable {
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

    @Override public void handle(Context context) throws IOException {
        byte[] body = context.bodyInputStream().readNBytes(8193);
        if (body.length > 8192) {
            HttpResponses.json(context, 413, "{\"message\":\"Request too large\"}");
            return;
        }
        String path = context.req().getRequestURI();
        String query = context.req().getQueryString();
        URI destination = URI.create(service.getScheme() + "://" + service.getRawAuthority()
                + path + (query == null ? "" : "?" + query));
        var request = HttpRequest.newBuilder(destination).timeout(Duration.ofSeconds(8))
                .method(context.method().name(), HttpRequest.BodyPublishers.ofByteArray(body));
        for (String name : Set.of("Cookie", "Content-Type", "Accept")) {
            String value = context.header(name);
            if (value != null) request.header(name, value);
        }
        HttpResponse<byte[]> response;
        try {
            response = client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException exception) {
            unavailable(context);
            return;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            unavailable(context);
            return;
        }
        for (String name : Set.of("Set-Cookie", "Allow", "Location")) {
            for (String value : response.headers().allValues(name)) context.addHeader(name, value);
        }
        String type = response.headers().firstValue("Content-Type").orElse("application/json").split(";", 2)[0];
        HttpResponses.send(context, response.statusCode(), type, response.body());
    }

    private void unavailable(Context context) {
        HttpResponses.json(context, 503, "{\"message\":\"Game service is unavailable. Try again shortly.\"}");
    }

    @Override public void close() { client.close(); }
}
