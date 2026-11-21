package software.ulpgc.chess.registerservice;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.ulpgc.chess.registerservice.application.domain.model.Account;
import software.ulpgc.chess.registerservice.application.ports.AccountRepository;
import software.ulpgc.chess.registerservice.infrastructure.adapters.SqliteAccountDatabase;
import software.ulpgc.chess.registerservice.infrastructure.adapters.SqliteAccountRepository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SqliteAccountRepositoryTest {
    @TempDir Path directory;

    @Test void reloadsIndependentAccountsAndAssociationsAfterRepeatedInitialization() {
        Path path = directory.resolve("accounts/accounts.sqlite");
        AccountRepository repository = repository(path);
        Account alice = Account.create("account'one", "Alice", "existing'guest-one");
        Account bob = Account.create("account-two", "bob", "guest-two").deactivate();
        assertTrue(repository.findByUsername(alice.username()).isEmpty());
        assertTrue(repository.findByPlayerId(alice.playerId()).isEmpty());

        repository.create(alice, "stored-test-hash");
        repository.create(bob, "stored-test-hash");

        AccountRepository reopened = repository(path);
        assertEquals(alice, reopened.findByUsername(" ALICE ").orElseThrow());
        assertEquals(alice, reopened.findByPlayerId(alice.playerId()).orElseThrow());
        assertEquals("stored-test-hash", reopened.findPasswordHash(alice.id()).orElseThrow());
        assertEquals(bob, reopened.findByUsername("bob").orElseThrow());
        assertEquals(bob, reopened.findByPlayerId(bob.playerId()).orElseThrow());
        assertTrue(reopened.findByPlayerId("' OR 1 = 1 --").isEmpty());
    }

    @Test void duplicateIdentifiersUsernamesAndPlayerAssociationsCannotReplaceAnAccount() {
        AccountRepository repository = repository(directory.resolve("accounts.sqlite"));
        Account original = Account.create("account-one", "alice", "existing-guest-id").deactivate();
        repository.create(original, "stored-test-hash");

        for (Account duplicate : List.of(
                original,
                Account.create(original.id(), "bob", "player-two"),
                Account.create("account-two", " ALICE ", "player-two"),
                Account.create("account-two", "bob", original.playerId()))) {
            assertThrows(IllegalArgumentException.class, () -> repository.create(duplicate, "stored-test-hash"));
            assertEquals(original, repository.findByUsername("alice").orElseThrow());
            assertEquals(original, repository.findByPlayerId(original.playerId()).orElseThrow());
            assertEquals("stored-test-hash", repository.findPasswordHash(original.id()).orElseThrow());
            assertTrue(repository.findByUsername("bob").isEmpty());
            assertTrue(repository.findByPlayerId("player-two").isEmpty());
        }
    }

    @Test void rejectsInvalidInputsWithoutChangingSavedAccounts() {
        AccountRepository repository = repository(directory.resolve("accounts.sqlite"));
        Account alice = Account.create("account-one", "alice", "player-one");
        repository.create(alice, "stored-test-hash");

        assertThrows(IllegalArgumentException.class, () -> repository.create(null, "stored-test-hash"));
        assertThrows(IllegalArgumentException.class, () -> repository.create(alice, null));
        assertThrows(IllegalArgumentException.class, () -> repository.create(alice, " "));
        for (String missing : new String[]{null, "", " \t"}) {
            assertThrows(IllegalArgumentException.class, () -> repository.findByUsername(missing));
            assertThrows(IllegalArgumentException.class, () -> repository.findByPlayerId(missing));
            assertThrows(IllegalArgumentException.class, () -> repository.findPasswordHash(missing));
            assertThrows(IllegalArgumentException.class, () -> repository.deactivate(missing));
        }
        assertEquals(alice, repository.findByUsername("alice").orElseThrow());
    }

    @Test void aRejectedWritePreservesExistingAccountsAndDoesNotLookLikeADuplicate() throws Exception {
        Path path = directory.resolve("accounts.sqlite");
        AccountRepository repository = repository(path);
        Account alice = Account.create("account-one", "alice", "player-one");
        Account bob = Account.create("account-two", "bob", "player-two");
        repository.create(alice, "stored-test-hash");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path);
             var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TRIGGER reject_new_account BEFORE INSERT ON accounts
                    BEGIN SELECT RAISE(ABORT, 'Test write failure'); END
                    """);
        }

        var failure = assertThrows(IllegalStateException.class, () -> repository.create(bob, "stored-test-hash"));

        assertEquals("Could not create the account.", failure.getMessage());
        assertInstanceOf(SQLException.class, failure.getCause());
        assertEquals(alice, repository(path).findByUsername("alice").orElseThrow());
        assertTrue(repository.findByUsername("bob").isEmpty());
        assertTrue(repository.findByPlayerId(bob.playerId()).isEmpty());
    }

    @Test void invalidStoredStatusIsReportedAsCorruptionRatherThanAMissingAccount() throws Exception {
        Path path = directory.resolve("accounts.sqlite");
        AccountRepository repository = repository(path);
        Account alice = Account.create("account-one", "alice", "player-one");
        repository.create(alice, "stored-test-hash");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path);
             var statement = connection.createStatement()) {
            statement.execute("PRAGMA ignore_check_constraints = ON");
            statement.executeUpdate("UPDATE accounts SET status = 'Unknown' WHERE username = 'alice'");
        }

        var failure = assertThrows(IllegalStateException.class, () -> repository.findByUsername("alice"));

        assertEquals("Could not load the account.", failure.getMessage());
        assertInstanceOf(IllegalArgumentException.class, failure.getCause());
    }

    @Test void initializationAndLookupFailuresPreserveTheirStorageCause() throws Exception {
        Path blocked = Files.createDirectory(directory.resolve("blocked.sqlite"));
        var initialization = assertThrows(IllegalStateException.class, () -> repository(blocked));
        assertEquals("Could not initialize account storage.", initialization.getMessage());
        assertInstanceOf(SQLException.class, initialization.getCause());

        var missingSchema = new SqliteAccountRepository(new SqliteAccountDatabase(directory.resolve("empty.sqlite")));
        var lookup = assertThrows(IllegalStateException.class, () -> missingSchema.findByUsername("alice"));
        assertEquals("Could not load the account.", lookup.getMessage());
        assertInstanceOf(SQLException.class, lookup.getCause());
    }

    @Test void aPasswordWriteFailureRollsBackTheAccountAndPreservesExistingCredentials() throws Exception {
        Path path = directory.resolve("accounts.sqlite");
        AccountRepository repository = repository(path);
        Account alice = Account.create("account-one", "alice", "player-one");
        Account bob = Account.create("account-two", "bob", "player-two");
        repository.create(alice, "alice-hash");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path);
             var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TRIGGER reject_password BEFORE INSERT ON account_passwords
                    BEGIN SELECT RAISE(ABORT, 'Test credential failure'); END
                    """);
        }

        var failure = assertThrows(IllegalStateException.class, () -> repository.create(bob, "bob-hash"));

        assertInstanceOf(SQLException.class, failure.getCause());
        AccountRepository reopened = repository(path);
        assertTrue(reopened.findByUsername("bob").isEmpty());
        assertTrue(reopened.findByPlayerId(bob.playerId()).isEmpty());
        assertTrue(reopened.findPasswordHash(bob.id()).isEmpty());
        assertEquals(alice, reopened.findByUsername("alice").orElseThrow());
        assertEquals("alice-hash", reopened.findPasswordHash(alice.id()).orElseThrow());
    }

    @Test void aFailedDeactivationPreservesStatusIdentityAndCredentials() throws Exception {
        Path path = directory.resolve("accounts.sqlite");
        AccountRepository repository = repository(path);
        Account alice = Account.create("account-one", "alice", "player-one");
        repository.create(alice, "alice-hash");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path);
             var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TRIGGER reject_deactivation BEFORE UPDATE ON accounts
                    BEGIN SELECT RAISE(ABORT, 'Test status failure'); END
                    """);
        }

        assertThrows(IllegalStateException.class, () -> repository.deactivate(alice.id()));
        assertEquals(alice, repository.findByUsername("alice").orElseThrow());
        assertEquals("alice-hash", repository.findPasswordHash(alice.id()).orElseThrow());
    }

    @Test void addingCredentialStoragePreservesLegacyAccountsWithoutInventingPasswords() throws Exception {
        Path path = directory.resolve("legacy.sqlite");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path);
             var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE accounts (id TEXT PRIMARY KEY NOT NULL, username TEXT NOT NULL UNIQUE,
                        player_id TEXT NOT NULL UNIQUE, status TEXT NOT NULL);
                    INSERT INTO accounts VALUES ('old-account', 'alice', 'old-player', 'Inactive');
                    """);
        }

        AccountRepository repository = repository(path);
        Account original = Account.create("old-account", "alice", "old-player").deactivate();
        assertEquals(original, repository.findByPlayerId("old-player").orElseThrow());
        assertTrue(repository.findPasswordHash(original.id()).isEmpty());
        assertEquals(original, repository(path).findByUsername("alice").orElseThrow());
        assertThrows(IllegalArgumentException.class, () -> repository.create(original, "replacement-hash"));

        var database = new SqliteAccountDatabase(path);
        try (var connection = database.openConnection();
             var insert = connection.prepareStatement("INSERT INTO account_passwords VALUES (?, ?)")) {
            insert.setString(1, "missing-account");
            insert.setString(2, "orphan-hash");
            assertThrows(SQLException.class, insert::executeUpdate);
        }
    }

    private AccountRepository repository(Path path) {
        var database = new SqliteAccountDatabase(path);
        database.initializeSchema();
        return new SqliteAccountRepository(database);
    }
}
