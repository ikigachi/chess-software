package software.ulpgc.chess.authenticationservice.application.ports;

import software.ulpgc.chess.authenticationservice.application.domain.model.AuthenticatedSession;

import java.time.Instant;
import java.util.Optional;

public interface SessionRepository {
    void create(String tokenHash, AuthenticatedSession session, Instant idleCutoff);

    Optional<AuthenticatedSession> resume(String tokenHash, Instant now, Instant idleCutoff);

    void revoke(String tokenHash);
}
