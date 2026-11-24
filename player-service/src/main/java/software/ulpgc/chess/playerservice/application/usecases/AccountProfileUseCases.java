package software.ulpgc.chess.playerservice.application.usecases;

import software.ulpgc.chess.application.domain.model.User;
import software.ulpgc.chess.playerservice.application.ports.AccountIdentity;
import software.ulpgc.chess.playerservice.application.ports.PlayerRepository;

import java.util.Objects;

public final class AccountProfileUseCases {
    private final PlayerRepository repository;
    private final AccountIdentity accounts;

    public AccountProfileUseCases(PlayerRepository repository, AccountIdentity accounts) {
        this.repository = Objects.requireNonNull(repository);
        this.accounts = Objects.requireNonNull(accounts);
    }

    public User loadProfile(String token) {
        String id = accounts.resolvePlayerId(token);
        return repository.findById(id).orElseGet(() -> new User(id, "Guest"));
    }

    public User updateProfile(String token, String name) {
        String normalizedName = User.normalizeName(name);
        User player = new User(accounts.resolvePlayerId(token), normalizedName);
        repository.save(player);
        return player;
    }
}
