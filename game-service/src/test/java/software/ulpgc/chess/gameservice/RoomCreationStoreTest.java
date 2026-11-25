package software.ulpgc.chess.gameservice;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.ulpgc.chess.application.domain.model.*;
import software.ulpgc.chess.gameservice.application.domain.model.RoomCreation;
import software.ulpgc.chess.gameservice.application.ports.RoomCreationStore;
import software.ulpgc.chess.gameservice.application.ports.RoomRepository;
import software.ulpgc.chess.gameservice.application.usecases.CreateRoomOnceUseCase;
import software.ulpgc.chess.gameservice.infrastructure.adapters.InMemoryRoomRepository;
import software.ulpgc.chess.gameservice.infrastructure.adapters.SqliteDatabase;
import software.ulpgc.chess.gameservice.infrastructure.adapters.SqliteRoomRepository;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class RoomCreationStoreTest {
    private final User white = new User("white", "Recorded Alice");
    private final User black = new User("black", "Recorded Bob");
    @TempDir Path directory;

    private SqliteDatabase database() {
        var database = new SqliteDatabase(directory.resolve("rooms.sqlite"));
        database.initialize();
        return database;
    }

    @Test void memoryAndSqliteProvideTheSameCreationContract() {
        var memory = new InMemoryRoomRepository();
        verifyCreationContract(memory, memory);
        var sqlite = new SqliteRoomRepository(database());
        verifyCreationContract(sqlite, sqlite);
    }

    private void verifyCreationContract(RoomRepository rooms, RoomCreationStore creations) {
        var create = new CreateRoomOnceUseCase(creations);
        Room initial = create.execute("operation-1", "ROOM01", white);
        Room played = initial.join(black).move(white, new Move(Square.at("e2"), Square.at("e4")));
        rooms.save(played);

        assertEquals(played, create.execute("operation-1", "ROOM01", white));
        assertThrows(IllegalArgumentException.class, () -> create.execute("operation-1", "ROOM02", white));
        assertTrue(rooms.findByCode("ROOM02").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> create.execute("operation-1", "ROOM01", black));
        assertThrows(IllegalArgumentException.class, () -> create.execute("operation-1", "ROOM01", new User("white", "Renamed")));
        assertThrows(IllegalArgumentException.class, () -> create.execute("operation-2", "ROOM01", white));
        assertEquals(played, rooms.findByCode("ROOM01").orElseThrow());
        assertEquals("ROOM02", create.execute("operation-2", "ROOM02", white).code());
        var distinctGame = new RoomCreation("game-key", new Room("ROOM03", white, null, Game.initial("game-3")));
        assertEquals(distinctGame.room(), creations.createOnce(distinctGame));
        assertEquals(distinctGame.room(), creations.createOnce(distinctGame));
        assertThrows(IllegalArgumentException.class, () -> creations.createOnce(
                new RoomCreation("game-key", new Room("ROOM03", white, null, Game.initial("another-game")))));
        assertEquals(distinctGame.room(), rooms.findByCode("ROOM03").orElseThrow());
        rooms.save(Room.create("ROOM01", black));
        assertThrows(IllegalStateException.class, () -> create.execute("operation-1", "ROOM01", white));
    }

    @Test void invalidRequestsNeverReachTheStore() {
        var create = new CreateRoomOnceUseCase(request -> { throw new AssertionError("Invalid creation reached storage."); });
        for (String key : new String[] {null, "", "   ", "a".repeat(129)})
            assertThrows(IllegalArgumentException.class, () -> create.execute(key, "ROOM01", white));
        assertThrows(IllegalArgumentException.class, () -> create.execute("key", " ", white));
        assertThrows(IllegalArgumentException.class, () -> create.execute("key", "ROOM01", null));
        assertThrows(IllegalArgumentException.class, () -> new RoomCreation("key", Room.create("ROOM01", white).join(black)));
        assertThrows(IllegalArgumentException.class, () -> new RoomCreation("key",
                new Room("ROOM01", white, null, Game.replay("game", java.util.List.of(new Move(Square.at("e2"), Square.at("e4")))))));
    }

    @Test void additiveSchemaAndRestartPreserveLegacyRoomsAndCompletedCreationKeys() throws Exception {
        var database = database();
        var repository = new SqliteRoomRepository(database);
        Room legacy = Room.create("LEGACY", white).join(black);
        repository.save(legacy);
        try (var connection = database.openConnection(); var statement = connection.createStatement()) {
            statement.executeUpdate("DROP TABLE room_creations");
        }
        database.initialize();
        database.initialize();
        var create = new CreateRoomOnceUseCase(repository);
        Room created = create.execute("persistent-key", "ROOM01", white);
        Room played = created.join(black).move(white, new Move(Square.at("e2"), Square.at("e4")));
        repository.save(played);

        var reopened = new SqliteRoomRepository(database());

        assertEquals(played, new CreateRoomOnceUseCase(reopened).execute("persistent-key", "ROOM01", white));
        assertEquals(legacy, reopened.findByCode("LEGACY").orElseThrow());
        assertThrows(IllegalArgumentException.class, () ->
                new CreateRoomOnceUseCase(reopened).execute("another-key", "LEGACY", white));
    }

    @Test void aFailedKeyWriteRollsBackTheRoomAndAllowsTheSameOperationToBeRetried() throws Exception {
        var database = database();
        var repository = new SqliteRoomRepository(database);
        var create = new CreateRoomOnceUseCase(repository);
        try (var connection = database.openConnection(); var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TRIGGER reject_creation BEFORE INSERT ON room_creations
                    BEGIN SELECT RAISE(ABORT, 'Test key write failure'); END
                    """);
        }

        var failure = assertThrows(IllegalStateException.class, () -> create.execute("key", "ROOM01", white));

        assertInstanceOf(SQLException.class, failure.getCause());
        assertTrue(repository.findByCode("ROOM01").isEmpty());
        try (var connection = database.openConnection(); var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("SELECT COUNT(*) FROM room_creations")) {
                assertTrue(rows.next());
                assertEquals(0, rows.getInt(1));
            }
            statement.executeUpdate("DROP TRIGGER reject_creation");
        }
        assertEquals(Room.create("ROOM01", white), create.execute("key", "ROOM01", white));
    }

    @Test void simultaneousMemoryRetriesReturnTheSameCreatedRoom() {
        var repository = new InMemoryRoomRepository();
        verifyConcurrentRetries(repository, repository);
    }

    @Test void simultaneousSqliteRetriesFromDifferentRepositoriesReturnTheSameCreatedRoom() {
        var database = database();
        verifyConcurrentRetries(new SqliteRoomRepository(database), new SqliteRoomRepository(database));
    }

    private void verifyConcurrentRetries(RoomCreationStore firstStore, RoomCreationStore secondStore) {
        var start = new CountDownLatch(1);
        var first = CompletableFuture.supplyAsync(() -> createAfterSignal(start, firstStore));
        var second = CompletableFuture.supplyAsync(() -> createAfterSignal(start, secondStore));
        start.countDown();
        assertEquals(Room.create("ROOM01", white), first.join());
        assertEquals(first.join(), second.join());
    }

    private Room createAfterSignal(CountDownLatch signal, RoomCreationStore store) {
        try {
            if (!signal.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Creation start timed out.");
            return new CreateRoomOnceUseCase(store).execute("concurrent-key", "ROOM01", white);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Creation was interrupted.", exception);
        }
    }
}
