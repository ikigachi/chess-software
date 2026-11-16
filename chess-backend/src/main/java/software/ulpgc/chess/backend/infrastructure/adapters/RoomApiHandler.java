package software.ulpgc.chess.backend.infrastructure.adapters;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import software.ulpgc.chess.application.domain.model.*;
import software.ulpgc.chess.backend.application.usecases.*;
import software.ulpgc.chess.backend.infrastructure.http.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class RoomApiHandler implements HttpHandler {
    private final CreateRoomUseCase create;
    private final GetRoomUseCase get;
    private final EnterRoomUseCase enter;
    private final MoveUseCase move;
    private final BrowserSessions sessions;
    private final RoomJsonCodec codec;

    public RoomApiHandler(CreateRoomUseCase create, GetRoomUseCase get, EnterRoomUseCase enter,
                          MoveUseCase move, BrowserSessions sessions, RoomJsonCodec codec) {
        this.create = Objects.requireNonNull(create);
        this.get = Objects.requireNonNull(get);
        this.enter = Objects.requireNonNull(enter);
        this.move = Objects.requireNonNull(move);
        this.sessions = Objects.requireNonNull(sessions);
        this.codec = Objects.requireNonNull(codec);
    }

    @Override public void handle(HttpExchange exchange) throws IOException {
        BrowserSessions.Session session = null;
        try {
            String path = exchange.getRequestURI().getPath();
            if (!Set.of("/api/state", "/api/action").contains(path)) {
                error(exchange, 404, ApiMessages.NOT_FOUND, null);
                return;
            }
            String method = path.equals("/api/state") ? "GET" : "POST";
            if (!exchange.getRequestMethod().equals(method)) {
                exchange.getResponseHeaders().set("Allow", method);
                error(exchange, 405, ApiMessages.METHOD_NOT_ALLOWED, null);
                return;
            }
            session = sessions.get(exchange);
            if (path.equals("/api/state")) {
                snapshot(exchange, session, current(session), "");
                return;
            }
            byte[] body = exchange.getRequestBody().readNBytes(8193);
            if (body.length > 8192) { error(exchange, 413, ApiMessages.REQUEST_TOO_LARGE, session); return; }
            Map<String, String> fields = FormData.decode(new String(body, StandardCharsets.UTF_8));
            if (!session.token.equals(fields.get("token"))) {
                error(exchange, 403, ApiMessages.INVALID_TOKEN, session);
                return;
            }
            RoomActionRequest request = RoomActionRequest.from(fields);
            Room room = action(session, request);
            snapshot(exchange, session, room, ApiMessages.success(request, room));
        } catch (IllegalArgumentException exception) {
            error(exchange, 400, ApiMessages.error(exception.getMessage()), session);
        } catch (IllegalStateException exception) {
            error(exchange, 503, ApiMessages.UNAVAILABLE, session);
        } catch (RuntimeException exception) {
            error(exchange, 500, ApiMessages.FAILED, session);
        } finally { exchange.close(); }
    }

    private Room action(BrowserSessions.Session session, RoomActionRequest request) {
        return switch (request.action()) {
            case "create", "join" -> {
                User user = new User(session.user.id(), request.name());
                Room room = request.action().equals("create") ? create.execute(user) : enter.execute(request.code(), user);
                session.user = user;
                session.code = room.code();
                yield room;
            }
            case "move" -> {
                if (session.code == null) throw new IllegalArgumentException("Enter the room code.");
                yield move.execute(session.code, session.user, request.move());
            }
            case "refresh" -> current(session);
            case "leave" -> { session.code = null; yield null; }
            default -> throw new IllegalArgumentException("Unknown action.");
        };
    }

    private Room current(BrowserSessions.Session session) {
        return session.code == null ? null : get.execute(session.code);
    }

    private void snapshot(HttpExchange exchange, BrowserSessions.Session session, Room room, String message) throws IOException {
        boolean whiteConnected = room != null && sessions.isPresent(room.code(), room.white());
        boolean blackConnected = room != null && sessions.isPresent(room.code(), room.black());
        HttpResponses.json(exchange, 200, codec.snapshot(room, session.user, session.token, message,
                whiteConnected, blackConnected));
    }

    private void error(HttpExchange exchange, int status, String message, BrowserSessions.Session session) throws IOException {
        HttpResponses.json(exchange, status, codec.error(message, session == null ? null : session.token));
    }

}
