package software.ulpgc.chess.gameservice.application.domain.model;

import software.ulpgc.chess.application.domain.model.Room;

public record RoomCreation(String operationKey, Room room) {
    public RoomCreation {
        if (operationKey == null || operationKey.isBlank() || operationKey.length() > 128)
            throw new IllegalArgumentException("Enter an operation key between 1 and 128 characters.");
        if (room == null || room.black() != null || !room.game().moves().isEmpty())
            throw new IllegalArgumentException("Creation requires a waiting room with its initial game.");
    }

    public void validateStoredRoom(Room stored) {
        if (stored == null || !room.code().equals(stored.code()) || !room.white().equals(stored.white())
                || !room.game().id().equals(stored.game().id()))
            throw new IllegalStateException("Stored room no longer matches its creation.");
    }
}
