package software.ulpgc.chess.gameservice;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.ulpgc.chess.application.domain.model.Move;
import software.ulpgc.chess.application.domain.model.Room;
import software.ulpgc.chess.application.domain.model.User;
import software.ulpgc.chess.gameservice.application.ports.RoomRepository;
import software.ulpgc.chess.gameservice.infrastructure.adapters.InMemoryRoomRepository;
import software.ulpgc.chess.gameservice.infrastructure.adapters.SqliteRoomRepository;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static software.ulpgc.chess.application.domain.model.Square.at;

class RoomRepositoryContractTest {
    @Test void inMemoryRepositorySupportsTheRoomWorkflow() {
        verifyRoomWorkflow(new InMemoryRoomRepository());
    }

    @Test void sqliteRepositorySupportsTheRoomWorkflow(@TempDir Path directory) {
        verifyRoomWorkflow(new SqliteRoomRepository(directory.resolve("rooms.sqlite")));
    }

    private void verifyRoomWorkflow(RoomRepository repository) {
        User white = new User("white", "Alice");
        User black = new User("black", "Bob");
        Room waiting = Room.create("GAME01", white);
        assertTrue(repository.findByCode(waiting.code()).isEmpty());

        repository.save(waiting);
        Room loaded = repository.findByCode(waiting.code()).orElseThrow();
        assertEquals(waiting, loaded);

        Room played = loaded.join(black)
                .move(white, new Move(at("e2"), at("e4")))
                .move(black, new Move(at("e7"), at("e5")));
        repository.save(played);

        assertEquals(played, repository.findByCode(waiting.code()).orElseThrow());
    }
}
