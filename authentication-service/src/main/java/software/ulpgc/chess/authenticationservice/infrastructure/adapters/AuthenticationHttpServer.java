package software.ulpgc.chess.authenticationservice.infrastructure.adapters;

import com.google.gson.JsonParseException;
import io.javalin.Javalin;
import io.javalin.http.MethodNotAllowedResponse;
import io.javalin.http.NotFoundResponse;

import java.net.URI;
import java.util.Objects;

public final class AuthenticationHttpServer implements AutoCloseable {
    private final Javalin server;
    private final int port;

    public AuthenticationHttpServer(int port, AuthenticationApiHandler authentication) {
        Objects.requireNonNull(authentication);
        this.port = port;
        server = Javalin.create(config -> {
            config.http.prefer405over404 = true;
            config.router.ignoreTrailingSlashes = false;
            config.routes.post("/sessions", authentication::login);
            config.routes.get("/sessions/current", authentication::resumeSession);
            config.routes.delete("/sessions/current", authentication::logout);
            config.routes.exception(SecurityException.class, (exception, context) ->
                    authentication.sendErrorResponse(context, 401, "Authentication credentials are invalid or expired."));
            config.routes.exception(JsonParseException.class, (exception, context) ->
                    authentication.sendErrorResponse(context, 400, "Invalid JSON request."));
            config.routes.exception(IllegalArgumentException.class, (exception, context) ->
                    authentication.sendErrorResponse(context, 400, exception.getMessage()));
            config.routes.exception(IllegalStateException.class, (exception, context) ->
                    authentication.sendErrorResponse(context, 503, "Authentication service is unavailable. Try again later."));
            config.routes.exception(NotFoundResponse.class, (exception, context) ->
                    authentication.sendErrorResponse(context, 404, "Not found"));
            config.routes.exception(MethodNotAllowedResponse.class, (exception, context) -> {
                context.header("Allow", context.path().equals("/sessions") ? "POST" : "GET, DELETE");
                authentication.sendErrorResponse(context, 405, "Method not allowed");
            });
            config.routes.exception(Exception.class, (exception, context) ->
                    authentication.sendErrorResponse(context, 500, "Authentication request failed."));
        });
    }

    public void start() { start("127.0.0.1"); }
    public void start(String bindAddress) { server.start(bindAddress, port); }
    public URI uri() { return URI.create("http://127.0.0.1:" + server.port()); }

    @Override public void close() { server.stop(); }
}
