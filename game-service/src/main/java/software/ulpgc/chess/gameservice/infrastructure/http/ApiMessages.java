package software.ulpgc.chess.gameservice.infrastructure.http;

import software.ulpgc.chess.application.domain.model.Room;

public final class ApiMessages {
    public static final String NOT_FOUND = "Not found";
    public static final String METHOD_NOT_ALLOWED = "Method not allowed";
    public static final String REQUEST_TOO_LARGE = "Request too large";
    public static final String INVALID_TOKEN = "Invalid form token";
    public static final String UNAVAILABLE = "The server cannot accept this request. Try again later.";
    public static final String FAILED = "The operation could not be completed.";

    private ApiMessages() {}

    public static String success(RoomActionRequest request, Room room) {
        return switch (request.action()) {
            case "create" -> "Room created. Share code " + room.code() + " with your opponent.";
            case "join" -> "You are in room " + room.code() + ".";
            case "move" -> "Move recorded: " + request.move().from().coordinate() + " → " + request.move().to().coordinate() + ".";
            case "leave" -> "You stopped viewing the room. Use the room code to reopen it.";
            default -> "Game refreshed.";
        };
    }

    public static String error(String message) {
        if (message == null) return FAILED;
        if (message.startsWith("Room not found:")) return "Room not found. Check the code and try again.";
        return switch (message) {
            case "Room is full" -> "The room already has two players.";
            case "Room must have two players before moving" -> "Wait for the second player to join.";
            case "Piece does not belong to the current turn", "It is not this user's turn" -> "It is your opponent’s turn.";
            default -> message;
        };
    }
}
