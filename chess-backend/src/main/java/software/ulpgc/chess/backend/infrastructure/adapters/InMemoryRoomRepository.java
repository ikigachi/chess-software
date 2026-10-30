package software.ulpgc.chess.backend.infrastructure.adapters;

import software.ulpgc.chess.application.domain.model.Room;
import software.ulpgc.chess.backend.application.ports.RoomRepository;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class InMemoryRoomRepository implements RoomRepository {
    private final Map<String, Room> rooms = new HashMap<>();

    @Override
    public Optional<Room> findByCode(String code) {
        if (code == null || code.isBlank())
            throw new IllegalArgumentException("Room code cannot be empty");
        return Optional.ofNullable(rooms.get(code));
    }

    @Override
    public void save(Room room) {
        if (room == null)
            throw new IllegalArgumentException("Room cannot be null");
        rooms.put(room.code(), room);
    }
}
