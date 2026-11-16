package software.ulpgc.chess.backend;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.ulpgc.chess.application.domain.model.*;
import software.ulpgc.chess.backend.infrastructure.adapters.SqliteRoomRepository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SqliteRoomRepositoryTest {
    @TempDir Path directory;

    @Test void reportsAPathThatSQLiteCannotOpen() throws Exception {
        Path database = Files.createDirectory(directory.resolve("rooms.sqlite"));

        var failure = assertThrows(IllegalStateException.class, () -> new SqliteRoomRepository(database));

        assertEquals("Could not initialize room storage.", failure.getMessage());
        assertInstanceOf(SQLException.class, failure.getCause());
    }

    @Test void reloadsWaitingAndPlayedRoomsWithTheRecordedPromotion() {
        Path database = directory.resolve("chess/rooms.sqlite");
        var repository = new SqliteRoomRepository(database);
        assertTrue(repository.findByCode("MISSING").isEmpty());
        User white = new User("white", "Zoë");
        User black = new User("black", "Renée");
        Room room = Room.create("GAME01", white);
        repository.save(room);
        assertEquals(room, new SqliteRoomRepository(database).findByCode(room.code()).orElseThrow());

        room = room.join(black);
        List<String> moves = List.of("a2a4", "h7h5", "a4a5", "h5h4", "a5a6", "h4h3", "a6b7", "h3g2");
        for (int index = 0; index < moves.size(); index++) {
            String notation = moves.get(index);
            room = room.move(index % 2 == 0 ? white : black, move(notation, null));
        }
        room = room.move(white, move("b7a8", Promotion.Knight));
        repository.save(room);

        Room restored = new SqliteRoomRepository(database).findByCode(room.code()).orElseThrow();
        assertEquals(room, restored);
        assertEquals(Piece.WhiteKnight, restored.game().pieceAt(Square.at("a8")));
    }

    @Test void aFailedSavePreservesBothPlayersAndTheExistingHistory() throws Exception {
        Path database = directory.resolve("rooms.sqlite");
        var repository = new SqliteRoomRepository(database);
        User white = new User("white", "Alice");
        User black = new User("black", "Bob");
        Room saved = Room.create("GAME01", white).join(black).move(white, move("e2e4", null));
        repository.save(saved);
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TRIGGER reject_second_move BEFORE INSERT ON moves WHEN NEW.ply = 2
                    BEGIN SELECT RAISE(ABORT, 'Test write failure'); END
                    """);
        }
        Room changed = new Room(saved.code(), new User(white.id(), "Renamed"), black,
                saved.move(black, move("e7e5", null)).game());

        assertThrows(IllegalStateException.class, () -> repository.save(changed));
        assertEquals(saved, new SqliteRoomRepository(database).findByCode(saved.code()).orElseThrow());
    }

    private Move move(String notation, Promotion promotion) {
        return new Move(Square.at(notation.substring(0, 2)), Square.at(notation.substring(2)), promotion);
    }
}
