package software.ulpgc.chess.gameservice.infrastructure.http;

import software.ulpgc.chess.application.domain.model.Move;
import software.ulpgc.chess.application.domain.model.Promotion;
import software.ulpgc.chess.application.domain.model.Square;
import software.ulpgc.chess.application.domain.model.User;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

public record RoomActionRequest(String action, String name, String code, Move move) {
    public RoomActionRequest {
        if (action == null || !Set.of("create", "join", "move", "refresh", "leave").contains(action))
            throw new IllegalArgumentException("Unknown action.");
        if (action.equals("create") || action.equals("join")) name = User.normalizeName(name);
        if (action.equals("join")) code = normalizeCode(code);
        if (action.equals("move") && move == null)
            throw new IllegalArgumentException("Move cannot be null");
    }

    public static RoomActionRequest from(Map<String, String> fields) {
        String action = fields.getOrDefault("action", "");
        Move move = "move".equals(action)
                ? parseMove(fields.get("from"), fields.get("to"), fields.get("promotion")) : null;
        return new RoomActionRequest(action, fields.get("name"), fields.get("code"), move);
    }

    public static Move parseMove(String from, String to, String promotion) {
        return new Move(Square.at(from), Square.at(to), promotion(promotion));
    }

    private static Promotion promotion(String value) {
        if (value == null) return null;
        return switch (value.strip().toLowerCase(Locale.ROOT)) {
            case "queen" -> Promotion.Queen;
            case "rook" -> Promotion.Rook;
            case "bishop" -> Promotion.Bishop;
            case "knight" -> Promotion.Knight;
            default -> throw new IllegalArgumentException("Choose a queen, rook, bishop, or knight for promotion.");
        };
    }

    private static String normalizeCode(String code) {
        if (code == null || code.isBlank()) throw new IllegalArgumentException("Enter the room code.");
        String normalized = code.strip().toUpperCase(Locale.ROOT);
        if (normalized.length() > 30) throw new IllegalArgumentException("The room code is too long.");
        return normalized;
    }
}
