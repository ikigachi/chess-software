package software.ulpgc.chess.gameservice.infrastructure.http;

import io.javalin.http.Context;

public final class HttpResponses {
    private HttpResponses() {}

    public static void json(Context context, int status, String json) {
        context.status(status);
        context.contentType("application/json; charset=UTF-8");
        context.header("Cache-Control", "no-store");
        context.header("X-Content-Type-Options", "nosniff");
        context.result(json);
    }
}
