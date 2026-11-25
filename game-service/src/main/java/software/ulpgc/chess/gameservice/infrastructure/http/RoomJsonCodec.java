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
        JsonObject fields = parseJsonObject(json);
        String operationKey = readOptionalString(fields, "operationKey");
        if (fields.has("operationKey") && operationKey == null)
            throw new IllegalArgumentException("Expected a string for operationKey.");
        return new CreateRequest(readOptionalString(fields, "name"), readOptionalString(fields, "token"),
                operationKey, operationKey == null ? null : readOptionalString(fields, "code"));
    }

    public MoveRequest moveRequest(String json) {
        JsonObject fields = parseJsonObject(json);
        return new MoveRequest(readOptionalString(fields, "from"), readOptionalString(fields, "to"),
                readOptionalString(fields, "promotion"), readOptionalString(fields, "token"));
    }

    public UpdatePlayerProfileRequest parsePlayerProfileUpdateRequest(String json) {
        JsonObject fields = parseJsonObject(json);
        return new UpdatePlayerProfileRequest(readOptionalString(fields, "name"), readOptionalString(fields, "token"));
    }

    private JsonObject parseJsonObject(String json) {
        try {
            JsonObject fields = gson.fromJson(json, JsonObject.class);
            if (fields == null) throw new IllegalArgumentException("Expected a JSON object.");
            return fields;
        } catch (JsonParseException exception) {
            throw new IllegalArgumentException("Invalid JSON request.", exception);
        }
    }

    private String readOptionalString(JsonObject fields, String name) {
        var value = fields.get(name);
        if (value == null || value.isJsonNull()) return null;
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
            throw new IllegalArgumentException("Expected a string for " + name + ".");
        return value.getAsString();
    }

    public record CreateRequest(String name, String token, String operationKey, String code) {}
    public record MoveRequest(String from, String to, String promotion, String token) {}
    public record UpdatePlayerProfileRequest(String name, String token) {}

    public String serializePlayerProfile(User player, String token) {
        return gson.toJson(new PlayerProfileResponse(user(player), token));
    }

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
    private record PlayerProfileResponse(UserView profile, String token) {}
}
