package software.ulpgc.chess.gameservice.infrastructure.adapters;

import io.javalin.http.Context;
import software.ulpgc.chess.application.domain.model.Room;
import software.ulpgc.chess.application.domain.model.User;
import software.ulpgc.chess.gameservice.application.usecases.CreateRoomUseCase;
import software.ulpgc.chess.gameservice.application.usecases.CreateRoomOnceUseCase;
import software.ulpgc.chess.gameservice.application.domain.model.RoomCreationConflictException;
import software.ulpgc.chess.gameservice.application.usecases.GetRoomUseCase;
import software.ulpgc.chess.gameservice.application.usecases.MoveUseCase;
import software.ulpgc.chess.gameservice.infrastructure.http.ApiMessages;
import software.ulpgc.chess.gameservice.infrastructure.http.HttpResponses;
import software.ulpgc.chess.gameservice.infrastructure.http.RoomJsonCodec;
import software.ulpgc.chess.gameservice.infrastructure.http.RoomActionRequest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;

public final class GameApiHandler {
    private final CreateRoomUseCase create;
    private final CreateRoomOnceUseCase createOnce;
    private final GetRoomUseCase get;
    private final MoveUseCase move;
    private final BrowserSessions sessions;
    private final RoomJsonCodec codec;

    public GameApiHandler(CreateRoomUseCase create, CreateRoomOnceUseCase createOnce, GetRoomUseCase get, MoveUseCase move,
                          BrowserSessions sessions, RoomJsonCodec codec) {
        this.create = Objects.requireNonNull(create);
        this.createOnce = Objects.requireNonNull(createOnce);
        this.get = Objects.requireNonNull(get);
        this.move = Objects.requireNonNull(move);
        this.sessions = Objects.requireNonNull(sessions);
        this.codec = Objects.requireNonNull(codec);
    }

    public void retrieve(Context context) {
        String code = context.pathParam("id").toUpperCase(Locale.ROOT);
        Room room;
        try {
            room = get.execute(code);
        } catch (IllegalArgumentException exception) {
            error(context, 404, "Game not found.");
            return;
        }
        HttpResponses.json(context, 200, codec.game(room));
    }

    public void create(Context context) throws IOException {
        String body = jsonBody(context);
        if (body == null) return;
        var request = codec.createRequest(body);
        var session = sessions.getOrCreateSession(context);
        if (!session.token.equals(request.token())) {
            error(context, 403, ApiMessages.INVALID_TOKEN);
            return;
        }
        String name = new RoomActionRequest("create", request.name(), null, null).name();
        User user = new User(session.user.id(), name);
        Room room;
        try {
            room = request.operationKey() == null ? create.execute(user)
                    : createOnce.execute(request.operationKey(), normalizeCreationCode(request.code()), user);
        } catch (RoomCreationConflictException exception) {
            error(context, 409, exception.getMessage());
            return;
        }
        session.user = user;
        session.code = room.code();
        context.header("Location", "/games/" + room.code());
        HttpResponses.json(context, 201, codec.game(room));
    }

    public void move(Context context) throws IOException {
        String body = jsonBody(context);
        if (body == null) return;
        var request = codec.moveRequest(body);
        var session = sessions.getOrCreateSession(context);
        if (!session.token.equals(request.token())) {
            error(context, 403, ApiMessages.INVALID_TOKEN);
            return;
        }
        String code = context.pathParam("id").toUpperCase(Locale.ROOT);
        Room room;
        try {
            room = move.execute(code, session.user,
                    RoomActionRequest.parseMove(request.from(), request.to(), request.promotion()));
        } catch (IllegalArgumentException exception) {
            if (!exception.getMessage().startsWith("Room not found:")) throw exception;
            error(context, 404, "Game not found.");
            return;
        }
        HttpResponses.json(context, 200, codec.game(room));
    }

    private String normalizeCreationCode(String code) {
        String normalized = code == null ? "" : code.strip().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z0-9_-]{1,30}"))
            throw new IllegalArgumentException("Use a room code of 1-30 letters, digits, hyphens or underscores.");
        return normalized;
    }

    private String jsonBody(Context context) throws IOException {
        String contentType = context.header("Content-Type");
        if (contentType == null || !contentType.split(";", 2)[0].strip().equalsIgnoreCase("application/json")) {
            error(context, 415, "Expected application/json.");
            return null;
        }
        byte[] body = context.bodyInputStream().readNBytes(8193);
        if (body.length > 8192) {
            error(context, 413, ApiMessages.REQUEST_TOO_LARGE);
            return null;
        }
        return new String(body, StandardCharsets.UTF_8);
    }

    public void error(Context context, int status, String message) {
        HttpResponses.json(context, status, codec.error(message, null));
    }
}
