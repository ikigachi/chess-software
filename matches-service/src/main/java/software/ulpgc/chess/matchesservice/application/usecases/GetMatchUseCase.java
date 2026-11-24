package software.ulpgc.chess.matchesservice.application.usecases;

import software.ulpgc.chess.application.domain.model.Room;
import software.ulpgc.chess.matchesservice.application.ports.GameReader;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

public final class GetMatchUseCase {
    private final GameReader games;

    public GetMatchUseCase(GameReader games) {
        this.games = Objects.requireNonNull(games);
    }

    public Optional<Room> findByCode(String code) {
        if (code == null || code.isBlank()) throw new IllegalArgumentException("Enter the room code.");
        String normalized = code.strip().toUpperCase(Locale.ROOT);
        if (normalized.length() > 30) throw new IllegalArgumentException("The room code is too long.");
        return games.findByRoomCode(normalized);
    }
}
