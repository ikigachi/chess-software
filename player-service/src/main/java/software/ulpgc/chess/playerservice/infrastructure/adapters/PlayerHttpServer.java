package software.ulpgc.chess.playerservice.infrastructure.adapters;

import com.google.gson.JsonParseException;
import io.javalin.Javalin;
import io.javalin.http.MethodNotAllowedResponse;
import io.javalin.http.NotFoundResponse;

import java.net.URI;
import java.util.Objects;

public final class PlayerHttpServer implements AutoCloseable {
    private final Javalin server;
    private final int port;

    public PlayerHttpServer(int port, PlayerApiHandler players) {
        Objects.requireNonNull(players);
        this.port = port;
        server = Javalin.create(config -> {
            config.http.prefer405over404 = true;
            config.router.ignoreTrailingSlashes = false;
            config.routes.post("/players/resolve", players::resolvePlayer);
            config.routes.put("/players/name", players::renamePlayer);
            config.routes.exception(JsonParseException.class, (exception, context) ->
                    players.sendErrorResponse(context, 400, "Invalid JSON request."));
            config.routes.exception(IllegalArgumentException.class, (exception, context) ->
                    players.sendErrorResponse(context, 400, exception.getMessage()));
            config.routes.exception(IllegalStateException.class, (exception, context) ->
                    players.sendErrorResponse(context, 503, "Player storage is unavailable. Try again later."));
            config.routes.exception(NotFoundResponse.class, (exception, context) ->
                    players.sendErrorResponse(context, 404, "Not found"));
            config.routes.exception(MethodNotAllowedResponse.class, (exception, context) -> {
                context.header("Allow", context.path().equals("/players/name") ? "PUT" : "POST");
                players.sendErrorResponse(context, 405, "Method not allowed");
            });
            config.routes.exception(Exception.class, (exception, context) ->
                    players.sendErrorResponse(context, 500, "Could not resolve the player identity."));
        });
    }

    public void start() { start("127.0.0.1"); }
    public void start(String bindAddress) { server.start(bindAddress, port); }
    public URI uri() { return URI.create("http://127.0.0.1:" + server.port()); }

    @Override public void close() { server.stop(); }
}
