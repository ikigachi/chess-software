package software.ulpgc.chess.gameservice.infrastructure.adapters;

import software.ulpgc.chess.application.domain.model.Room;
import software.ulpgc.chess.gameservice.application.ports.RoomRepository;
import software.ulpgc.chess.gameservice.application.ports.RoomCreationStore;
import software.ulpgc.chess.gameservice.application.domain.model.RoomCreation;
import software.ulpgc.chess.gameservice.application.domain.model.RoomCreationConflictException;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class InMemoryRoomRepository implements RoomRepository, RoomCreationStore {
    private final Map<String, Room> rooms = new HashMap<>();
    private final Map<String, RoomCreation> creations = new HashMap<>();

    @Override
    public synchronized Optional<Room> findByCode(String code) {
        if (code == null || code.isBlank())
            throw new IllegalArgumentException("Room code cannot be empty");
        return Optional.ofNullable(rooms.get(code));
    }

    @Override
    public synchronized void save(Room room) {
        if (room == null)
            throw new IllegalArgumentException("Room cannot be null");
        rooms.put(room.code(), room);
    }

    @Override public synchronized Room createOnce(RoomCreation creation) {
        if (creation == null) throw new IllegalArgumentException("Room creation cannot be null.");
        RoomCreation previous = creations.get(creation.operationKey());
        if (previous != null) {
            if (!previous.equals(creation))
                throw new RoomCreationConflictException("Creation key was already used for a different request.");
            Room stored = rooms.get(previous.room().code());
            creation.validateStoredRoom(stored);
            return stored;
        }
        if (rooms.containsKey(creation.room().code()))
            throw new RoomCreationConflictException("Room already exists: " + creation.room().code());
        rooms.put(creation.room().code(), creation.room());
        creations.put(creation.operationKey(), creation);
        return creation.room();
    }
}
