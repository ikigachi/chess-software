package software.ulpgc.chess.playerservice.application.usecases;

import software.ulpgc.chess.application.domain.model.User;
import software.ulpgc.chess.playerservice.application.ports.PlayerRepository;

import java.util.Objects;

public final class RenamePlayerUseCase {
    private final ResolvePlayerUseCase resolve;
    private final PlayerRepository repository;

    public RenamePlayerUseCase(ResolvePlayerUseCase resolve, PlayerRepository repository) {
        this.resolve = Objects.requireNonNull(resolve);
        this.repository = Objects.requireNonNull(repository);
    }

    public User execute(String credential, String name) {
        User current = resolve.execute(credential);
        User renamed = new User(current.id(), name);
        repository.save(renamed);
        return renamed;
    }
}
