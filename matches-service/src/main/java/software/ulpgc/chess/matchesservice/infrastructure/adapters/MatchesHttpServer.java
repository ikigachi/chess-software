package software.ulpgc.chess.matchesservice.infrastructure.adapters;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.MethodNotAllowedResponse;
import io.javalin.http.NotFoundResponse;
import software.ulpgc.chess.application.domain.model.Color;
import software.ulpgc.chess.application.domain.model.Room;
import software.ulpgc.chess.application.domain.model.User;
import software.ulpgc.chess.matchesservice.application.usecases.GetMatchUseCase;

import java.net.URI;
import java.util.Objects;

public final class MatchesHttpServer implements AutoCloseable {
    private final Javalin server;
    private final int port;
    private final GetMatchUseCase matches;
    private final Gson gson = new GsonBuilder().serializeNulls().create();

    public MatchesHttpServer(int port, GetMatchUseCase matches) {
        this.port = port;
        this.matches = Objects.requireNonNull(matches);
        server = Javalin.create(config -> {
            config.http.prefer405over404 = true;
            config.router.ignoreTrailingSlashes = false;
            config.routes.get("/matches/{code}", this::retrieveMatch);
            config.routes.exception(IllegalArgumentException.class, (exception, context) ->
                    sendJsonResponse(context, 400, new ErrorView(exception.getMessage())));
            config.routes.exception(IllegalStateException.class, (exception, context) ->
                    sendJsonResponse(context, 503, new ErrorView("Game service is unavailable. Try again later.")));
            config.routes.exception(NotFoundResponse.class, (exception, context) ->
                    sendJsonResponse(context, 404, new ErrorView("Not found")));
            config.routes.exception(MethodNotAllowedResponse.class, (exception, context) -> {
                context.header("Allow", "GET");
                sendJsonResponse(context, 405, new ErrorView("Method not allowed"));
            });
            config.routes.exception(Exception.class, (exception, context) ->
                    sendJsonResponse(context, 500, new ErrorView("Could not query the match.")));
        });
    }

    private void retrieveMatch(Context context) {
        var match = matches.findByCode(context.pathParam("code"));
        if (match.isEmpty()) {
            sendJsonResponse(context, 404, new ErrorView("Match not found."));
            return;
        }
        Room room = match.orElseThrow();
        var state = room.game().state();
        sendJsonResponse(context, 200, new MatchView(room.code(), room.game().id(), userView(room.white()), userView(room.black()),
                state.turn().name(), state.isInCheck(), state.winner().map(Color::name).orElse(null),
                state.isStalemate(), room.game().moves().size()));
    }

    private UserView userView(User user) { return user == null ? null : new UserView(user.id(), user.name()); }

    private void sendJsonResponse(Context context, int status, Object value) {
        context.status(status).contentType("application/json; charset=UTF-8");
        context.header("Cache-Control", "no-store");
        context.header("X-Content-Type-Options", "nosniff");
        context.result(gson.toJson(value));
    }

    public void start() { start("127.0.0.1"); }
    public void start(String bindAddress) { server.start(bindAddress, port); }
    public URI uri() { return URI.create("http://127.0.0.1:" + server.port()); }
    @Override public void close() { server.stop(); }

    private record MatchView(String code, String gameId, UserView white, UserView black, String turn,
                             boolean inCheck, String winner, boolean stalemate, int moveCount) {}
    private record UserView(String id, String name) {}
    private record ErrorView(String message) {}
}
