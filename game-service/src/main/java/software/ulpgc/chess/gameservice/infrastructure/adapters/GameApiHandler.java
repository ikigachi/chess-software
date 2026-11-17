package software.ulpgc.chess.gameservice.infrastructure.adapters;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import software.ulpgc.chess.gameservice.application.usecases.GetRoomUseCase;
import software.ulpgc.chess.gameservice.infrastructure.http.ApiMessages;
import software.ulpgc.chess.gameservice.infrastructure.http.HttpResponses;
import software.ulpgc.chess.gameservice.infrastructure.http.RoomJsonCodec;

import java.io.IOException;
import java.util.Locale;
import java.util.Objects;

public final class GameApiHandler implements HttpHandler {
    private final GetRoomUseCase get;
    private final RoomJsonCodec codec;

    public GameApiHandler(GetRoomUseCase get, RoomJsonCodec codec) {
        this.get = Objects.requireNonNull(get);
        this.codec = Objects.requireNonNull(codec);
    }

    @Override public void handle(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            if (!path.startsWith("/games/") || path.length() == 7 || path.substring(7).contains("/")) {
                error(exchange, 404, ApiMessages.NOT_FOUND);
                return;
            }
            if (!exchange.getRequestMethod().equals("GET")) {
                exchange.getResponseHeaders().set("Allow", "GET");
                error(exchange, 405, ApiMessages.METHOD_NOT_ALLOWED);
                return;
            }
            String code = path.substring(7).toUpperCase(Locale.ROOT);
            HttpResponses.json(exchange, 200, codec.game(get.execute(code)));
        } catch (IllegalArgumentException exception) {
            error(exchange, 404, "Game not found.");
        } catch (IllegalStateException exception) {
            error(exchange, 503, ApiMessages.UNAVAILABLE);
        } catch (RuntimeException exception) {
            error(exchange, 500, ApiMessages.FAILED);
        } finally {
            exchange.close();
        }
    }

    private void error(HttpExchange exchange, int status, String message) throws IOException {
        HttpResponses.json(exchange, status, codec.error(message, null));
    }
}
