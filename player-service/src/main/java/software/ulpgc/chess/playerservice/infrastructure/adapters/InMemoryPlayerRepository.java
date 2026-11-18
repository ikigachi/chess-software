package software.ulpgc.chess.playerservice.infrastructure.adapters;

import software.ulpgc.chess.application.domain.model.User;
import software.ulpgc.chess.playerservice.application.ports.PlayerRepository;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryPlayerRepository implements PlayerRepository {
    private final Map<String, User> players = new ConcurrentHashMap<>();

    @Override public Optional<User> findById(String id) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Player identifier cannot be empty.");
        return Optional.ofNullable(players.get(id));
    }

    @Override public void save(User user) {
        if (user == null) throw new IllegalArgumentException("Player cannot be null.");
        players.put(user.id(), user);
    }
}
