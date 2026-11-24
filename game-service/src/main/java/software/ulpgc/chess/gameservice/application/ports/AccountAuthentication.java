package software.ulpgc.chess.gameservice.application.ports;

import java.time.Instant;

public interface AccountAuthentication {
    Grant login(String username, String password);

    Identity resume(String token);

    void logout(String token);

    record Identity(String accountId, String playerId, Instant expiresAt) {
        public Identity {
            if (accountId == null || accountId.isBlank() || playerId == null || playerId.isBlank() || expiresAt == null)
                throw new IllegalArgumentException("Account session is incomplete.");
        }
    }

    record Grant(String token, Identity identity) {
        public Grant {
            if (token == null || !token.matches("[A-Za-z0-9_-]{43}") || identity == null)
                throw new IllegalArgumentException("Account session grant is invalid.");
        }
    }
}
