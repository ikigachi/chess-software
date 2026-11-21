package software.ulpgc.chess.registerservice.application.ports;

import software.ulpgc.chess.registerservice.application.domain.model.Account;

import java.util.Optional;

public interface AccountRepository {
    void create(Account account, String passwordHash);

    Optional<Account> findByUsername(String username);

    Optional<Account> findByPlayerId(String playerId);

    Optional<String> findPasswordHash(String accountId);

    void deactivate(String accountId);
}
