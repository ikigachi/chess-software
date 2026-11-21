package software.ulpgc.chess.registerservice;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.ulpgc.chess.registerservice.application.domain.model.Account;
import software.ulpgc.chess.registerservice.application.domain.model.AccountConflictException;
import software.ulpgc.chess.registerservice.application.ports.AccountRepository;
import software.ulpgc.chess.registerservice.application.ports.PasswordHasher;
import software.ulpgc.chess.registerservice.application.ports.PlayerIdentity;
import software.ulpgc.chess.registerservice.application.usecases.AccountUseCases;
import software.ulpgc.chess.registerservice.infrastructure.adapters.SqliteAccountDatabase;
import software.ulpgc.chess.registerservice.infrastructure.adapters.SqliteAccountRepository;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AccountUseCasesTest {
    @TempDir Path directory;
    private static final String PASSWORD = "a long chess passphrase";

    @Test void preservesTheResolvedPlayerAndRejectsDuplicatesBeforeHashingAgain() {
        AccountRepository repository = repository();
        AtomicInteger hashes = new AtomicInteger();
        var useCases = new AccountUseCases(repository, credential -> "player-" + credential, passwords(hashes));

        Account registered = useCases.registerAccount("one", " Alice ", PASSWORD);

        assertEquals("player-one", registered.playerId());
        assertEquals("alice", registered.username());
        assertTrue(registered.isActive());
        assertEquals(registered, repository.findByPlayerId("player-one").orElseThrow());
        assertEquals("test-hash", repository.findPasswordHash(registered.id()).orElseThrow());
        assertThrows(AccountConflictException.class, () -> useCases.registerAccount("two", "ALICE", PASSWORD));
        assertThrows(AccountConflictException.class, () -> useCases.registerAccount("one", "bob", PASSWORD));
        assertEquals(1, hashes.get());
        assertTrue(repository.findByUsername("bob").isEmpty());
        assertTrue(repository.findByPlayerId("player-two").isEmpty());
    }

    @Test void rejectsInvalidInputBeforeIdentityResolutionOrHashingAndAcceptsUnicodePasswords() {
        PlayerIdentity identities = credential -> { throw new AssertionError("Identity must not be resolved."); };
        var invalidRequests = new AccountUseCases(repository(), identities, passwords(new AtomicInteger()));
        for (String password : new String[]{null, "", "too short", "a".repeat(129)})
            assertThrows(IllegalArgumentException.class, () -> invalidRequests.registerAccount("one", "alice", password));
        assertThrows(IllegalArgumentException.class, () -> invalidRequests.registerAccount("one", "?", PASSWORD));

        AccountRepository repository = repository();
        var validRequests = new AccountUseCases(repository, credential -> "player-one", passwords(new AtomicInteger()));
        assertTrue(validRequests.registerAccount("one", "alice", "♞".repeat(15)).isActive());
    }

    @Test void deactivationRequiresTheMatchingPlayerAndPasswordAndPreservesIdentifiersAndHash() {
        AccountRepository repository = repository();
        var useCases = new AccountUseCases(repository, credential -> "player-" + credential, passwords(new AtomicInteger()));
        Account active = useCases.registerAccount("one", "alice", PASSWORD);

        assertThrows(SecurityException.class, () -> useCases.deactivateAccount("two", PASSWORD));
        assertThrows(SecurityException.class, () -> useCases.deactivateAccount("one", "the wrong chess password"));
        assertEquals(active, repository.findByUsername("alice").orElseThrow());

        Account inactive = useCases.deactivateAccount("one", PASSWORD);

        assertEquals(active.deactivate(), inactive);
        assertEquals(inactive, repository.findByUsername("alice").orElseThrow());
        assertEquals("test-hash", repository.findPasswordHash(active.id()).orElseThrow());
        assertThrows(SecurityException.class, () -> useCases.deactivateAccount("one", PASSWORD));
        assertThrows(AccountConflictException.class, () -> useCases.registerAccount("one", "other-name", PASSWORD));
    }

    @Test void aHashingFailureCannotLeaveAPartiallyCreatedAccount() {
        AccountRepository repository = repository();
        PasswordHasher unavailable = new PasswordHasher() {
            @Override public String hash(String password) { throw new IllegalStateException("Hashing failed."); }
            @Override public boolean matches(String password, String hash) { throw new AssertionError(); }
        };
        var useCases = new AccountUseCases(repository, credential -> "player-one", unavailable);

        assertThrows(IllegalStateException.class, () -> useCases.registerAccount("one", "alice", PASSWORD));
        assertTrue(repository.findByUsername("alice").isEmpty());
        assertTrue(repository.findByPlayerId("player-one").isEmpty());
    }

    private PasswordHasher passwords(AtomicInteger hashes) {
        return new PasswordHasher() {
            @Override public String hash(String password) {
                hashes.incrementAndGet();
                return "test-hash";
            }
            @Override public boolean matches(String password, String hash) {
                return PASSWORD.equals(password) && "test-hash".equals(hash);
            }
        };
    }

    private AccountRepository repository() {
        var database = new SqliteAccountDatabase(directory.resolve("accounts.sqlite"));
        database.initializeSchema();
        return new SqliteAccountRepository(database);
    }
}
