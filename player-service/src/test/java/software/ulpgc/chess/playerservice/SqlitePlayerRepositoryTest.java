package software.ulpgc.chess.playerservice;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.ulpgc.chess.application.domain.model.User;
import software.ulpgc.chess.playerservice.infrastructure.adapters.SqlitePlayerDatabase;
import software.ulpgc.chess.playerservice.infrastructure.adapters.SqlitePlayerRepository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;

class SqlitePlayerRepositoryTest {
    @TempDir Path directory;

    @Test void reportsAPathThatCannotBeOpenedWithoutLosingTheCause() throws Exception {
        Path path = Files.createDirectory(directory.resolve("players.sqlite"));
        var database = new SqlitePlayerDatabase(path);

        var failure = assertThrows(IllegalStateException.class, database::initializeSchema);

        assertEquals("Could not initialize player storage.", failure.getMessage());
        assertInstanceOf(SQLException.class, failure.getCause());
    }

    @Test void aRejectedWritePreservesTheProfileAfterReinitializingStorage() throws Exception {
        Path path = directory.resolve("players.sqlite");
        var database = new SqlitePlayerDatabase(path);
        database.initializeSchema();
        var repository = new SqlitePlayerRepository(database);
        User saved = new User("player", "Alice");
        repository.save(saved);
        try (var connection = database.openConnection(); var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TRIGGER reject_name_changes BEFORE UPDATE ON players
                    BEGIN SELECT RAISE(ABORT, 'Test write failure'); END
                    """);
        }

        var failure = assertThrows(IllegalStateException.class, () -> repository.save(new User(saved.id(), "Renamed")));
        assertInstanceOf(SQLException.class, failure.getCause());
        var restarted = new SqlitePlayerDatabase(path);
        restarted.initializeSchema();
        assertEquals(saved, new SqlitePlayerRepository(restarted).findById(saved.id()).orElseThrow());
    }

    @Test void invalidStoredDataIsReportedAsAStorageError() throws Exception {
        var database = new SqlitePlayerDatabase(directory.resolve("players.sqlite"));
        database.initializeSchema();
        try (var connection = database.openConnection();
             var statement = connection.prepareStatement("INSERT INTO players (id, name) VALUES (?, ?)")) {
            statement.setString(1, "broken");
            statement.setString(2, " ");
            statement.executeUpdate();
        }

        var failure = assertThrows(IllegalStateException.class, () -> new SqlitePlayerRepository(database).findById("broken"));

        assertEquals("Could not load the player.", failure.getMessage());
        assertInstanceOf(IllegalArgumentException.class, failure.getCause());
    }
}
