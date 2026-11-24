package software.ulpgc.chess.matchesservice.application.ports;

import software.ulpgc.chess.application.domain.model.Room;

import java.util.Optional;

public interface GameReader {
    Optional<Room> findByRoomCode(String code);
}
