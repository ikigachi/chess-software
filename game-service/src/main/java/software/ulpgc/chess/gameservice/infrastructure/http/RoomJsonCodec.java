package software.ulpgc.chess.gameservice.infrastructure.http;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.Strictness;
import software.ulpgc.chess.application.domain.model.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class RoomJsonCodec {
    private final Gson gson = new GsonBuilder().serializeNulls().setStrictness(Strictness.STRICT).create();

    public CreateRequest createRequest(String json) {
        JsonObject fields = object(json);
        return new CreateRequest(string(fields, "name"), string(fields, "token"));
    }

    public MoveRequest moveRequest(String json) {
        JsonObject fields = object(json);
        return new MoveRequest(string(fields, "from"), string(fields, "to"),
                string(fields, "promotion"), string(fields, "token"));
    }

    private JsonObject object(String json) {
        try {
            JsonObject fields = gson.fromJson(json, JsonObject.class);
            if (fields == null) throw new IllegalArgumentException("Expected a JSON object.");
            return fields;
        } catch (JsonParseException exception) {
            throw new IllegalArgumentException("Invalid JSON request.", exception);
        }
    }

    private String string(JsonObject fields, String name) {
        var value = fields.get(name);
        if (value == null || value.isJsonNull()) return null;
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
            throw new IllegalArgumentException("Expected a string for " + name + ".");
        return value.getAsString();
    }

    public record CreateRequest(String name, String token) {}
    public record MoveRequest(String from, String to, String promotion, String token) {}

    public String snapshot(Room room, User viewer, String token, String message, boolean whiteConnected, boolean blackConnected) {
        Board board = room == null ? Board.initial() : room.game().state().board();
        PresenceView presence = room == null ? null : new PresenceView(whiteConnected, blackConnected);
        return gson.toJson(new Snapshot(user(viewer), roomView(room), pieces(board), presence, token, message));
    }

    public String game(Room room) {
        return gson.toJson(new GameSnapshot(room.game().id(), roomView(room), pieces(room.game().state().board())));
    }

    private Map<String, String> pieces(Board board) {
        Map<String, String> pieces = new LinkedHashMap<>();
        board.pieces().forEach((square, piece) -> pieces.put(square.coordinate(), piece.name()));
        return pieces;
    }

    public String error(String message, String token) {
        return gson.toJson(new ErrorResponse(message, token));
    }

    private RoomView roomView(Room room) {
        if (room == null) return null;
        Game game = room.game();
        State state = game.state();
        return new RoomView(room.code(), state.turn().name(), state.isInCheck(), state.winner().map(Color::name).orElse(null),
                state.isStalemate(), user(room.white()), user(room.black()), game.moves().stream().map(this::moveView).toList());
    }

    private MoveView moveView(Move move) {
        String promotion = move.promotion() == null ? null : move.promotion().name().toLowerCase(Locale.ROOT);
        return new MoveView(move.from().coordinate(), move.to().coordinate(), promotion);
    }

    private UserView user(User user) {
        return user == null ? null : new UserView(user.id(), user.name());
    }

    private record Snapshot(UserView viewer, RoomView room, Map<String, String> pieces, PresenceView presence,
                            String token, String message) {}
    private record GameSnapshot(String id, RoomView room, Map<String, String> pieces) {}
    private record PresenceView(boolean whiteConnected, boolean blackConnected) {}
    private record UserView(String id, String name) {}
    private record RoomView(String code, String turn, boolean inCheck, String winner, boolean stalemate,
                            UserView white, UserView black, List<MoveView> moves) {}
    private record MoveView(String from, String to, String promotion) {}
    private record ErrorResponse(String message, String token) {}
}
