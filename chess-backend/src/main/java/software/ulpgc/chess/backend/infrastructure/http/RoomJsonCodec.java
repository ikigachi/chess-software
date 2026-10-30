package software.ulpgc.chess.backend.infrastructure.http;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import software.ulpgc.chess.application.domain.model.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class RoomJsonCodec {
    private final Gson gson = new GsonBuilder().serializeNulls().create();

    public String snapshot(Room room, User viewer, String token, String message) {
        Board board = room == null ? Board.initial() : room.game().state().board();
        Map<String, String> pieces = new LinkedHashMap<>();
        board.pieces().forEach((square, piece) -> pieces.put(square.coordinate(), piece.name()));
        RoomView roomView = room == null ? null : new RoomView(room.code(), room.game().turn().name(),
                user(room.white()), user(room.black()), room.game().moves().stream()
                .map(move -> new MoveView(move.from().coordinate(), move.to().coordinate())).toList());
        return gson.toJson(new Snapshot(user(viewer), roomView, pieces, token, message));
    }

    public String error(String message, String token) {
        return gson.toJson(new ErrorResponse(message, token));
    }

    private UserView user(User user) {
        return user == null ? null : new UserView(user.id(), user.name());
    }

    private record Snapshot(UserView viewer, RoomView room, Map<String, String> pieces, String token, String message) {}
    private record UserView(String id, String name) {}
    private record RoomView(String code, String turn, UserView white, UserView black, List<MoveView> moves) {}
    private record MoveView(String from, String to) {}
    private record ErrorResponse(String message, String token) {}
}
