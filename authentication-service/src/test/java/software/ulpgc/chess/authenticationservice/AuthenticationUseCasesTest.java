package software.ulpgc.chess.authenticationservice;

import org.junit.jupiter.api.Test;
import software.ulpgc.chess.authenticationservice.application.domain.model.AccountCredentials;
import software.ulpgc.chess.authenticationservice.application.domain.model.AuthenticatedSession;
import software.ulpgc.chess.authenticationservice.application.ports.*;
import software.ulpgc.chess.authenticationservice.application.usecases.AuthenticationUseCases;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class AuthenticationUseCasesTest {
    @Test void applicationPortsCanBeSubstitutedWithoutDependingOnSqliteOrPassword4j() {
        var stored = new AtomicReference<AuthenticatedSession>();
        var credentials = new AccountCredentials("account-one", "stable-player-one", "encoded-hash");
        AccountCredentialsReader reader = username -> username.equals("alice") ? Optional.of(credentials) : Optional.empty();
        PasswordVerifier verifier = (password, hash) -> password.equals("test passphrase") && hash.equals(Optional.of("encoded-hash"));
        var useCases = useCases(reader, verifier, sessions(stored));

        var grant = useCases.login(" ALICE ", "test passphrase");

        assertEquals("opaque-token", grant.token());
        assertEquals("stable-player-one", grant.session().playerId());
        assertEquals(grant.session(), useCases.resumeSession(grant.token()));
        useCases.logout(grant.token());
        assertNull(stored.get());
        assertThrows(SecurityException.class, () -> useCases.resumeSession(grant.token()));
    }

    @Test void anUnknownAccountStillRunsPasswordVerificationAndNeverCreatesASession() {
        var stored = new AtomicReference<AuthenticatedSession>();
        AtomicInteger verifications = new AtomicInteger();
        PasswordVerifier verifier = (password, hash) -> {
            verifications.incrementAndGet();
            assertTrue(hash.isEmpty());
            return false;
        };
        var useCases = useCases(username -> Optional.empty(), verifier, sessions(stored));

        assertThrows(SecurityException.class, () -> useCases.login("unknown", "test passphrase"));

        assertEquals(1, verifications.get());
        assertNull(stored.get());
    }

    private AuthenticationUseCases useCases(AccountCredentialsReader reader, PasswordVerifier verifier, SessionRepository sessions) {
        SessionTokens tokens = new SessionTokens() {
            @Override public String generate() { return "opaque-token"; }
            @Override public String digest(String token) { return "digest:" + token; }
        };
        return new AuthenticationUseCases(reader, verifier, sessions, tokens,
                Clock.fixed(Instant.parse("2026-11-21T12:00:00Z"), ZoneOffset.UTC), Duration.ofHours(8), Duration.ofMinutes(30));
    }

    private SessionRepository sessions(AtomicReference<AuthenticatedSession> stored) {
        return new SessionRepository() {
            @Override public void create(String hash, AuthenticatedSession session, Instant cutoff) {
                assertEquals("digest:opaque-token", hash);
                stored.set(session);
            }
            @Override public Optional<AuthenticatedSession> resume(String hash, Instant now, Instant cutoff) {
                return Optional.ofNullable(stored.get());
            }
            @Override public void revoke(String hash) { stored.set(null); }
        };
    }
}
