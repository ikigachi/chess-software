package software.ulpgc.chess.backend.application.usecases;

import software.ulpgc.chess.application.domain.model.Room;
import software.ulpgc.chess.application.domain.model.User;
import software.ulpgc.chess.backend.application.ports.RoomRepository;

import java.util.Objects;

public class JoinRoomUseCase {
    private final RoomRepository repository;

    public JoinRoomUseCase(RoomRepository repository) {
        this.repository = Objects.requireNonNull(repository);
    }

    public Room execute(String code, User user) {
        Room room = repository.findByCode(code)
                .orElseThrow(() -> new IllegalArgumentException("Room not found: " + code));
        Room joined = room.join(user);
        repository.save(joined);
        return joined;
    }
}
