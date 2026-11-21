package software.ulpgc.chess.registerservice.application.ports;

import software.ulpgc.chess.registerservice.application.domain.model.Account;

import java.util.Optional;

public interface AccountRepository {
    void create(Account account);

    Optional<Account> findByUsername(String username);

    Optional<Account> findByPlayerId(String playerId);
}
