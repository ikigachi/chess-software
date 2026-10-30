package software.ulpgc.chess.backend.application.usecases;

import software.ulpgc.chess.application.domain.model.Room;
import software.ulpgc.chess.application.domain.model.User;
import software.ulpgc.chess.backend.application.ports.RoomRepository;

import java.util.Objects;
import java.util.Locale;
import java.util.UUID;

public class CreateRoomUseCase {
    private final RoomRepository repository;

    public CreateRoomUseCase(RoomRepository repository) {
        this.repository = Objects.requireNonNull(repository);
    }

    public Room execute(User creator) {
        String code;
        do {
            code = UUID.randomUUID().toString().substring(0, 6).toUpperCase(Locale.ROOT);
        } while (repository.findByCode(code).isPresent());
        return execute(code, creator);
    }

    public Room execute(String code, User creator) {
        Room room = Room.create(code, creator);
        if (repository.findByCode(code).isPresent())
            throw new IllegalArgumentException("Room already exists: " + code);
        repository.save(room);
        return room;
    }
}
