package software.ulpgc.chess.authenticationservice.application.usecases;

import software.ulpgc.chess.authenticationservice.application.domain.model.AuthenticatedSession;
import software.ulpgc.chess.authenticationservice.application.domain.model.AccountCredentials;
import software.ulpgc.chess.authenticationservice.application.ports.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

public final class AuthenticationUseCases {
    private final AccountCredentialsReader accounts;
    private final PasswordVerifier passwords;
    private final SessionRepository sessions;
    private final SessionTokens tokens;
    private final Clock clock;
    private final Duration lifetime;
    private final Duration idleTimeout;

    public AuthenticationUseCases(AccountCredentialsReader accounts, PasswordVerifier passwords, SessionRepository sessions,
                                  SessionTokens tokens, Clock clock, Duration lifetime, Duration idleTimeout) {
        this.accounts = Objects.requireNonNull(accounts);
        this.passwords = Objects.requireNonNull(passwords);
        this.sessions = Objects.requireNonNull(sessions);
        this.tokens = Objects.requireNonNull(tokens);
        this.clock = Objects.requireNonNull(clock);
        this.lifetime = Objects.requireNonNull(lifetime);
        this.idleTimeout = Objects.requireNonNull(idleTimeout);
        if (lifetime.toMillis() <= 0 || idleTimeout.toMillis() <= 0 || idleTimeout.compareTo(lifetime) > 0)
            throw new IllegalArgumentException("Session timeouts must be positive and idle timeout cannot exceed lifetime.");
    }

    public SessionGrant login(String username, String password) {
        if (password == null || password.isEmpty() || password.codePointCount(0, password.length()) > 128)
            throw new SecurityException("Account credentials are invalid.");
        String name = username == null ? "" : username.strip();
        Optional<AccountCredentials> credentials = name.matches("[A-Za-z0-9._-]{3,40}")
                ? accounts.findActiveByUsername(name.toLowerCase(Locale.ROOT)) : Optional.empty();
        if (!passwords.matches(password, credentials.map(AccountCredentials::passwordHash)) || credentials.isEmpty())
            throw new SecurityException("Account credentials are invalid.");
        AccountCredentials account = credentials.orElseThrow();
        Instant now = Instant.ofEpochMilli(clock.millis());
        var session = new AuthenticatedSession(account.accountId(), account.playerId(), now, now, now.plus(lifetime));
        String token = tokens.generate();
        sessions.create(tokens.digest(token), session, now.minus(idleTimeout));
        return new SessionGrant(token, session);
    }

    public AuthenticatedSession resumeSession(String token) {
        Instant now = Instant.ofEpochMilli(clock.millis());
        return sessions.resume(tokens.digest(token), now, now.minus(idleTimeout))
                .orElseThrow(() -> new SecurityException("Session is invalid or expired."));
    }

    public void logout(String token) {
        sessions.revoke(tokens.digest(token));
    }

    public record SessionGrant(String token, AuthenticatedSession session) {}
}
