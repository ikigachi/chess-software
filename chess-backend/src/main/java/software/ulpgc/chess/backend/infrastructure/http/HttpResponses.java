package software.ulpgc.chess.backend.infrastructure.http;

import io.javalin.http.Context;

import java.nio.charset.StandardCharsets;

public final class HttpResponses {
    private HttpResponses() {}

    public static void text(Context context, int status, String message) {
        send(context, status, "text/plain", message.getBytes(StandardCharsets.UTF_8));
    }

    public static void json(Context context, int status, String json) {
        send(context, status, "application/json", json.getBytes(StandardCharsets.UTF_8));
    }

    public static void send(Context context, int status, String type, byte[] body) {
        context.status(status);
        context.contentType(type + (type.startsWith("image/") ? "" : "; charset=UTF-8"));
        context.header("Cache-Control", "no-store");
        context.header("X-Content-Type-Options", "nosniff");
        context.header("Content-Security-Policy", "default-src 'self'; script-src 'self'; connect-src 'self'; img-src 'self'; style-src 'self'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'");
        context.result(body);
    }
}
