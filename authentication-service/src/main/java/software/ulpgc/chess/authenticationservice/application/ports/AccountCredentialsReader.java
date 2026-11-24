package software.ulpgc.chess.authenticationservice.application.ports;

import software.ulpgc.chess.authenticationservice.application.domain.model.AccountCredentials;

import java.util.Optional;

public interface AccountCredentialsReader {
    Optional<AccountCredentials> findActiveByUsername(String username);
}
