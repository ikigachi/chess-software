package software.ulpgc.chess.backend.application.usecases;

import software.ulpgc.chess.application.domain.model.Move;
import software.ulpgc.chess.application.domain.model.Room;
import software.ulpgc.chess.application.domain.model.User;
import software.ulpgc.chess.backend.application.ports.RoomRepository;

import java.util.Objects;

public class MoveUseCase {
    private final RoomRepository repository;

    public MoveUseCase(RoomRepository repository) {
        this.repository = Objects.requireNonNull(repository);
    }

    public Room execute(String code, User user, Move move) {
        Room room = repository.findByCode(code)
                .orElseThrow(() -> new IllegalArgumentException("Room not found: " + code));
        Room moved = room.move(user, move);
        repository.save(moved);
        return moved;
    }
}
