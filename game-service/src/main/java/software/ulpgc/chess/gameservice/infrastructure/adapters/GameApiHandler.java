package software.ulpgc.chess.gameservice.infrastructure.adapters;

import io.javalin.http.Context;
import software.ulpgc.chess.application.domain.model.Room;
import software.ulpgc.chess.application.domain.model.User;
import software.ulpgc.chess.gameservice.application.usecases.CreateRoomUseCase;
import software.ulpgc.chess.gameservice.application.usecases.GetRoomUseCase;
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
    private final GetRoomUseCase get;
    private final BrowserSessions sessions;
    private final RoomJsonCodec codec;

    public GameApiHandler(CreateRoomUseCase create, GetRoomUseCase get, BrowserSessions sessions, RoomJsonCodec codec) {
        this.create = Objects.requireNonNull(create);
        this.get = Objects.requireNonNull(get);
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
        String contentType = context.header("Content-Type");
        if (contentType == null || !contentType.split(";", 2)[0].strip().equalsIgnoreCase("application/json")) {
            error(context, 415, "Expected application/json.");
            return;
        }
        byte[] body = context.bodyInputStream().readNBytes(8193);
        if (body.length > 8192) {
            error(context, 413, ApiMessages.REQUEST_TOO_LARGE);
            return;
        }
        var request = codec.createRequest(new String(body, StandardCharsets.UTF_8));
        var session = sessions.get(context);
        if (!session.token.equals(request.token())) {
            error(context, 403, ApiMessages.INVALID_TOKEN);
            return;
        }
        String name = new RoomActionRequest("create", request.name(), null, null).name();
        User user = new User(session.user.id(), name);
        Room room = create.execute(user);
        session.user = user;
        session.code = room.code();
        context.header("Location", "/games/" + room.game().id());
        HttpResponses.json(context, 201, codec.game(room));
    }

    public void error(Context context, int status, String message) {
        HttpResponses.json(context, status, codec.error(message, null));
    }
}
