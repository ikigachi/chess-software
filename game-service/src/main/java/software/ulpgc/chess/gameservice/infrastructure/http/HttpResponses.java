package software.ulpgc.chess.gameservice.infrastructure.http;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class HttpResponses {
    private HttpResponses() {}

    public static void text(HttpExchange exchange, int status, String message) throws IOException {
        send(exchange, status, "text/plain", message.getBytes(StandardCharsets.UTF_8));
    }

    public static void json(HttpExchange exchange, int status, String json) throws IOException {
        send(exchange, status, "application/json", json.getBytes(StandardCharsets.UTF_8));
    }

    public static void send(HttpExchange exchange, int status, String type, byte[] body) throws IOException {
        var headers = exchange.getResponseHeaders();
        headers.set("Content-Type", type + (type.startsWith("image/") ? "" : "; charset=UTF-8"));
        headers.set("Cache-Control", "no-store");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Content-Security-Policy", "default-src 'self'; script-src 'self'; connect-src 'self'; img-src 'self'; style-src 'self'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }
}
