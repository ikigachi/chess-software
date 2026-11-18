package software.ulpgc.chess.gameservice.infrastructure.adapters;

import io.javalin.Javalin;
import io.javalin.http.MethodNotAllowedResponse;
import io.javalin.http.NotFoundResponse;
import software.ulpgc.chess.gameservice.infrastructure.http.ApiMessages;

import java.net.URI;
import java.util.Objects;

public final class GameHttpServer implements AutoCloseable {
    private final Javalin server;
    private final int port;

    public GameHttpServer(int port, RoomApiHandler rooms, GameApiHandler games, PlayerProfileApiHandler profiles) {
        Objects.requireNonNull(rooms);
        Objects.requireNonNull(games);
        Objects.requireNonNull(profiles);
        this.port = port;
        server = Javalin.create(config -> {
            config.http.prefer405over404 = true;
            config.router.ignoreTrailingSlashes = false;
            config.router.handlerWrapper(endpoint -> context -> {
                synchronized (rooms) {
                    endpoint.handler.handle(context);
                }
            });
            config.routes.get("/api/state", rooms);
            config.routes.post("/api/action", rooms);
            config.routes.get("/api/profile", profiles);
            config.routes.put("/api/profile", profiles);
            config.routes.get("/games/{id}", games::retrieve);
            config.routes.put("/games/{id}", games::move);
            config.routes.post("/games", games::create);
            config.routes.exception(NotFoundResponse.class, (exception, context) ->
                    games.error(context, 404, ApiMessages.NOT_FOUND));
            config.routes.exception(MethodNotAllowedResponse.class, (exception, context) -> {
                String allowed = context.path().equals("/games") || context.path().equals("/api/action") ? "POST" : "GET";
                if (context.path().startsWith("/games/") || context.path().equals("/api/profile")) allowed = "GET, PUT";
                context.header("Allow", allowed);
                games.error(context, 405, ApiMessages.METHOD_NOT_ALLOWED);
            });
            config.routes.exception(IllegalArgumentException.class, (exception, context) ->
                    games.error(context, 400, exception.getMessage()));
            config.routes.exception(IllegalStateException.class, (exception, context) ->
                    games.error(context, 503, ApiMessages.UNAVAILABLE));
            config.routes.exception(Exception.class, (exception, context) ->
                    games.error(context, 500, ApiMessages.FAILED));
        });
    }

    public void start() { start("127.0.0.1"); }
    public void start(String bindAddress) { server.start(bindAddress, port); }
    public URI uri() { return URI.create("http://127.0.0.1:" + server.port()); }

    @Override public void close() { server.stop(); }
}
