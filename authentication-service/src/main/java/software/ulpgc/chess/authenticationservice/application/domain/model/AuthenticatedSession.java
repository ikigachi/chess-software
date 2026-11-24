package software.ulpgc.chess.authenticationservice.application.domain.model;

import java.time.Instant;

public record AuthenticatedSession(String accountId, String playerId, Instant createdAt, Instant lastSeenAt, Instant expiresAt) {
    public AuthenticatedSession {
        if (accountId == null || accountId.isBlank() || playerId == null || playerId.isBlank())
            throw new IllegalArgumentException("Session account and player identifiers are required.");
        if (createdAt == null || lastSeenAt == null || expiresAt == null || lastSeenAt.isBefore(createdAt)
                || !expiresAt.isAfter(lastSeenAt))
            throw new IllegalArgumentException("Session timestamps are invalid.");
    }
}
