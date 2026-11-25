package software.ulpgc.chess.gameservice.application.usecases;

import software.ulpgc.chess.application.domain.model.Room;
import software.ulpgc.chess.application.domain.model.User;
import software.ulpgc.chess.gameservice.application.domain.model.RoomCreation;
import software.ulpgc.chess.gameservice.application.ports.RoomCreationStore;

import java.util.Objects;

public final class CreateRoomOnceUseCase {
    private final RoomCreationStore creations;

    public CreateRoomOnceUseCase(RoomCreationStore creations) {
        this.creations = Objects.requireNonNull(creations);
    }

    public Room execute(String operationKey, String code, User creator) {
        return creations.createOnce(new RoomCreation(operationKey, Room.create(code, creator)));
    }
}
