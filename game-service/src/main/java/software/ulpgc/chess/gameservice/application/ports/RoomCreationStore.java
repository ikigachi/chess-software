package software.ulpgc.chess.gameservice.application.ports;

import software.ulpgc.chess.application.domain.model.Room;
import software.ulpgc.chess.gameservice.application.domain.model.RoomCreation;

public interface RoomCreationStore {
    Room createOnce(RoomCreation creation);
}
