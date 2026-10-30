package software.ulpgc.chess.backend.application.ports;

import software.ulpgc.chess.application.domain.model.Room;

import java.util.Optional;

public interface RoomRepository {
    Optional<Room> findByCode(String code);

    void save(Room room);
}
