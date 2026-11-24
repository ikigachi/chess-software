package software.ulpgc.chess.authenticationservice;

import org.junit.jupiter.api.Test;
import software.ulpgc.chess.authenticationservice.application.domain.model.AccountCredentials;
import software.ulpgc.chess.authenticationservice.application.domain.model.AuthenticatedSession;
import software.ulpgc.chess.authenticationservice.infrastructure.adapters.SecureSessionTokens;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class AuthenticationModelTest {
    @Test void sessionsRequireAnIdentityAndOrderedTimestamps() {
        Instant now = Instant.parse("2026-11-21T12:00:00Z");
        assertThrows(IllegalArgumentException.class, () -> new AuthenticatedSession(null, "player", now, now, now.plusSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new AuthenticatedSession("account", " ", now, now, now.plusSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new AuthenticatedSession("account", "player", now, now.minusSeconds(1), now.plusSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new AuthenticatedSession("account", "player", now, now, now));
        assertThrows(IllegalArgumentException.class, () -> new AccountCredentials("account", "player", " "));
    }

    @Test void sessionTokensAreIndependentCanonicalSecretsWithStableOneWayDigests() {
        var tokens = new SecureSessionTokens();
        String first = tokens.generate();
        String second = tokens.generate();
        assertNotEquals(first, second);
        assertEquals(43, first.length());
        assertTrue(tokens.digest(first).matches("[a-f0-9]{64}"));
        assertNotEquals(first, tokens.digest(first));
        assertEquals(tokens.digest(first), new SecureSessionTokens().digest(first));
        for (String invalid : new String[]{null, "", "a".repeat(42), first + "=", "!".repeat(43), "_".repeat(43)})
            assertThrows(SecurityException.class, () -> tokens.digest(invalid));
    }
}
