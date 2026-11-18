package software.ulpgc.chess.playerservice.application.ports;

import software.ulpgc.chess.application.domain.model.User;

import java.util.Optional;

public interface PlayerRepository {
    Optional<User> findById(String id);

    void save(User user);
}
