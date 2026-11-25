package software.ulpgc.chess.gameservice.application.domain.model;

public final class RoomCreationConflictException extends IllegalArgumentException {
    public RoomCreationConflictException(String message) {
        super(message);
    }
}
