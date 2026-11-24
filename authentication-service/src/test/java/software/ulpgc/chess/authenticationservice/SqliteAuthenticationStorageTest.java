package software.ulpgc.chess.authenticationservice;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.ulpgc.chess.authenticationservice.application.domain.model.AuthenticatedSession;
import software.ulpgc.chess.authenticationservice.infrastructure.adapters.*;
import software.ulpgc.chess.registerservice.application.domain.model.Account;
import software.ulpgc.chess.registerservice.infrastructure.adapters.SqliteAccountDatabase;
import software.ulpgc.chess.registerservice.infrastructure.adapters.SqliteAccountRepository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class SqliteAuthenticationStorageTest {
    @TempDir Path directory;
    private static final Instant NOW = Instant.parse("2026-11-21T12:00:00Z");
    private static final String HASH = "a".repeat(64);
    private static final Account ALICE = Account.create("account-one", "alice", "existing-player-one");

    @Test void initializationRequiresAnExistingAccountDatabaseAndNeverCreatesAccountTables() throws Exception {
        Path missing = directory.resolve("missing.sqlite");
        var unavailable = new SqliteAuthenticationDatabase(missing);

        var failure = assertThrows(IllegalStateException.class, unavailable::initializeSchema);

        assertInstanceOf(SQLException.class, failure.getCause());
        assertFalse(Files.exists(missing));
        Path empty = Files.createFile(directory.resolve("empty.sqlite"));
        var uninitialized = new SqliteAuthenticationDatabase(empty);
        assertThrows(IllegalStateException.class, uninitialized::initializeSchema);
        try (var connection = uninitialized.openConnection();
             var query = connection.prepareStatement("SELECT count(*) FROM sqlite_master WHERE type = 'table'");
             var rows = query.executeQuery()) {
            assertTrue(rows.next());
            assertEquals(0, rows.getInt(1));
        }
    }

    @Test void preservesAccountsAndSessionsAcrossInitializationAndIndependentConnections() throws Exception {
        var database = database();
        var repository = new SqliteSessionRepository(database);
        var session = session(ALICE);
        repository.create(HASH, session, NOW.minusSeconds(1800));

        var reopened = new SqliteAuthenticationDatabase(path());
        reopened.initializeSchema();
        var restored = new SqliteSessionRepository(reopened).resume(HASH, NOW.plusSeconds(60), NOW.minusSeconds(1740)).orElseThrow();

        assertEquals(ALICE.id(), restored.accountId());
        assertEquals(ALICE.playerId(), restored.playerId());
        assertEquals(session.expiresAt(), restored.expiresAt());
        assertEquals(NOW.plusSeconds(60), restored.lastSeenAt());
        assertEquals("stored-password-hash", new SqliteAccountCredentialsReader(reopened)
                .findActiveByUsername("alice").orElseThrow().passwordHash());
        try (var connection = reopened.openConnection();
             var query = connection.prepareStatement("SELECT token_hash FROM authentication_sessions");
             var rows = query.executeQuery()) {
            assertTrue(rows.next());
            assertEquals(HASH, rows.getString(1));
        }
    }

    @Test void enforcesIdleAndAbsoluteBoundariesAndAnInactiveAccountCannotResumeOrCreateSessions() {
        var database = database();
        var sessions = new SqliteSessionRepository(database);
        sessions.create(HASH, session(ALICE), NOW.minusSeconds(1800));
        assertTrue(sessions.resume(HASH, NOW.plusSeconds(1800), NOW).isEmpty());
        assertTrue(sessions.resume(HASH, NOW.plusSeconds(28800), NOW.minusSeconds(1)).isEmpty());
        var accounts = new SqliteAccountRepository(new SqliteAccountDatabase(path()));
        accounts.deactivate(ALICE.id());

        assertTrue(new SqliteAccountCredentialsReader(database).findActiveByUsername("alice").isEmpty());
        assertTrue(sessions.resume(HASH, NOW.plusSeconds(1), NOW.minusSeconds(1799)).isEmpty());
        assertThrows(SecurityException.class, () -> sessions.create("b".repeat(64), session(ALICE), NOW.minusSeconds(1800)));
        assertEquals(ALICE.deactivate(), accounts.findByUsername("alice").orElseThrow());
    }

    @Test void failedSessionCreationRollsBackCleanupAndPreservesEarlierSessions() throws Exception {
        var database = database();
        var sessions = new SqliteSessionRepository(database);
        sessions.create(HASH, session(ALICE), NOW.minusSeconds(1800));
        try (var connection = database.openConnection(); var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TRIGGER reject_session BEFORE INSERT ON authentication_sessions
                    BEGIN SELECT RAISE(ABORT, 'Test session failure'); END
                    """);
        }
        Instant later = NOW.plusSeconds(3600);
        var next = new AuthenticatedSession(ALICE.id(), ALICE.playerId(), later, later, later.plusSeconds(28800));

        var failure = assertThrows(IllegalStateException.class, () -> sessions.create("b".repeat(64), next, later.minusSeconds(1800)));

        assertInstanceOf(SQLException.class, failure.getCause());
        try (var connection = database.openConnection();
             var query = connection.prepareStatement("SELECT token_hash, last_seen_at FROM authentication_sessions");
             var rows = query.executeQuery()) {
            assertTrue(rows.next());
            assertEquals(HASH, rows.getString(1));
            assertEquals(NOW.toEpochMilli(), rows.getLong(2));
            assertFalse(rows.next());
        }
    }

    @Test void aFailedResumeOrLogoutCannotReportSuccessOrChangeStoredActivity() throws Exception {
        var database = database();
        var sessions = new SqliteSessionRepository(database);
        sessions.create(HASH, session(ALICE), NOW.minusSeconds(1800));
        try (var connection = database.openConnection(); var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TRIGGER reject_activity BEFORE UPDATE ON authentication_sessions
                    BEGIN SELECT RAISE(ABORT, 'Test activity failure'); END;
                    CREATE TRIGGER reject_logout BEFORE DELETE ON authentication_sessions
                    BEGIN SELECT RAISE(ABORT, 'Test logout failure'); END;
                    """);
        }

        assertThrows(IllegalStateException.class, () -> sessions.resume(HASH, NOW.plusSeconds(60), NOW.minusSeconds(1740)));
        assertThrows(IllegalStateException.class, () -> sessions.revoke(HASH));
        try (var connection = database.openConnection();
             var query = connection.prepareStatement("SELECT last_seen_at FROM authentication_sessions WHERE token_hash = ?")) {
            query.setString(1, HASH);
            try (var rows = query.executeQuery()) {
                assertTrue(rows.next());
                assertEquals(NOW.toEpochMilli(), rows.getLong(1));
            }
        }
    }

    @Test void invalidSessionIdentityAndCorruptCredentialsDoNotCreateOrHideUsableSessions() throws Exception {
        var database = database();
        var sessions = new SqliteSessionRepository(database);
        var wrongPlayer = new AuthenticatedSession(ALICE.id(), "another-player", NOW, NOW, NOW.plusSeconds(28800));
        assertThrows(SecurityException.class, () -> sessions.create(HASH, wrongPlayer, NOW.minusSeconds(1800)));
        for (String invalid : new String[]{null, "", "A".repeat(64), "x".repeat(64)})
            assertThrows(IllegalArgumentException.class, () -> sessions.revoke(invalid));
        try (var connection = database.openConnection(); var statement = connection.createStatement()) {
            statement.execute("PRAGMA ignore_check_constraints = ON");
            statement.executeUpdate("UPDATE account_passwords SET password_hash = ''");
        }
        assertThrows(IllegalStateException.class, () -> new SqliteAccountCredentialsReader(database).findActiveByUsername("alice"));
    }

    private SqliteAuthenticationDatabase database() {
        var accountsDatabase = new SqliteAccountDatabase(path());
        accountsDatabase.initializeSchema();
        new SqliteAccountRepository(accountsDatabase).create(ALICE, "stored-password-hash");
        var database = new SqliteAuthenticationDatabase(path());
        database.initializeSchema();
        return database;
    }

    private Path path() { return directory.resolve("shared accounts #1.sqlite"); }

    private AuthenticatedSession session(Account account) {
        return new AuthenticatedSession(account.id(), account.playerId(), NOW, NOW, NOW.plusSeconds(28800));
    }
}
