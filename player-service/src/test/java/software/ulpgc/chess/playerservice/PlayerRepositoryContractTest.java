package software.ulpgc.chess.playerservice;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.ulpgc.chess.application.domain.model.User;
import software.ulpgc.chess.playerservice.application.ports.PlayerRepository;
import software.ulpgc.chess.playerservice.infrastructure.adapters.InMemoryPlayerRepository;
import software.ulpgc.chess.playerservice.infrastructure.adapters.SqlitePlayerDatabase;
import software.ulpgc.chess.playerservice.infrastructure.adapters.SqlitePlayerRepository;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PlayerRepositoryContractTest {
    @Test void inMemoryStoragePreservesIndependentProfilesAndRejectsInvalidInputs() {
        verifyProfiles(new InMemoryPlayerRepository());
    }

    @Test void sqliteStoragePreservesIndependentProfilesAndRejectsInvalidInputs(@TempDir Path directory) {
        var database = new SqlitePlayerDatabase(directory.resolve("profiles/players.sqlite"));
        database.initializeSchema();
        verifyProfiles(new SqlitePlayerRepository(database));
    }

    private void verifyProfiles(PlayerRepository repository) {
        User first = new User("player'one", "Zo\u00eb");
        User second = new User("player-two", "Ren\u00e9e");
        assertTrue(repository.findById(first.id()).isEmpty());
        repository.save(first);
        repository.save(second);
        assertEquals(first, repository.findById(first.id()).orElseThrow());
        assertEquals(second, repository.findById(second.id()).orElseThrow());

        User renamed = new User(first.id(), "New name");
        repository.save(renamed);
        assertEquals(renamed, repository.findById(first.id()).orElseThrow());
        assertEquals(second, repository.findById(second.id()).orElseThrow());
        assertTrue(repository.findById("' OR 1 = 1 --").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> repository.findById(null));
        assertThrows(IllegalArgumentException.class, () -> repository.findById(" "));
        assertThrows(IllegalArgumentException.class, () -> repository.save(null));
        assertEquals(renamed, repository.findById(first.id()).orElseThrow());
    }
}
