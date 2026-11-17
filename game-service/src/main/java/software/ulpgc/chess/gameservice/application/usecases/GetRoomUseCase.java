package software.ulpgc.chess.gameservice.application.usecases;

import software.ulpgc.chess.application.domain.model.Room;
import software.ulpgc.chess.gameservice.application.ports.RoomRepository;

import java.util.Objects;

public class GetRoomUseCase {
    private final RoomRepository repository;

    public GetRoomUseCase(RoomRepository repository) {
        this.repository = Objects.requireNonNull(repository);
    }

    public Room execute(String code) {
        return repository.findByCode(code)
                .orElseThrow(() -> new IllegalArgumentException("Room not found: " + code));
    }
}
