package software.ulpgc.chess.backend;

import org.junit.jupiter.api.Test;
import software.ulpgc.chess.application.domain.model.Color;
import software.ulpgc.chess.application.domain.model.Move;
import software.ulpgc.chess.application.domain.model.Room;
import software.ulpgc.chess.application.domain.model.User;
import software.ulpgc.chess.backend.application.ports.RoomRepository;
import software.ulpgc.chess.backend.application.usecases.CreateRoomUseCase;
import software.ulpgc.chess.backend.application.usecases.GetRoomUseCase;
import software.ulpgc.chess.backend.application.usecases.JoinRoomUseCase;
import software.ulpgc.chess.backend.application.usecases.EnterRoomUseCase;
import software.ulpgc.chess.backend.application.usecases.MoveUseCase;
import software.ulpgc.chess.backend.infrastructure.adapters.InMemoryRoomRepository;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static software.ulpgc.chess.application.domain.model.Square.at;

class RoomUseCasesTest {
    @Test
    void generatedRoomCodesAreUniqueAndStored() {
        RoomRepository repository = new InMemoryRoomRepository();
        CreateRoomUseCase create = new CreateRoomUseCase(repository);
        User user = new User("creator", "Alice");
        Room first = create.execute(user);
        Room second = create.execute(user);
        assertTrue(first.code().matches("[A-F0-9]{6}"));
        assertNotEquals(first.code(), second.code());
        assertEquals(first, repository.findByCode(first.code()).orElseThrow());
        assertEquals(second, repository.findByCode(second.code()).orElseThrow());
    }

    private final RoomRepository repository = new InMemoryRoomRepository();
    private final CreateRoomUseCase create = new CreateRoomUseCase(repository);
    private final GetRoomUseCase get = new GetRoomUseCase(repository);
    private final JoinRoomUseCase join = new JoinRoomUseCase(repository);
    private final MoveUseCase move = new MoveUseCase(repository);
    private final User white = new User("white", "Ana");
    private final User black = new User("black", "Luis");
    private final Move first = new Move(at("e2"), at("e4"));
    private final Move second = new Move(at("e7"), at("e5"));

    @Test
    void createsJoinsMovesAndRetrievesSharedRoom() {
        Room waiting = create.execute("room-1", white);
        assertSame(waiting, get.execute("room-1"));
        Room ready = join.execute("room-1", black);
        Room afterWhite = move.execute("room-1", white, first);
        Room afterBlack = move.execute("room-1", black, second);

        assertSame(afterBlack, get.execute("room-1"));
        assertEquals(List.of(first, second), afterBlack.game().moves());
        assertEquals(Color.White, afterBlack.game().turn());
        assertNull(waiting.black());
        assertTrue(ready.game().moves().isEmpty());
        assertEquals(List.of(first), afterWhite.game().moves());
    }

    @Test
    void duplicateCreationDoesNotReplaceExistingRoom() {
        create.execute("room-1", white);
        Room ready = join.execute("room-1", black);
        assertThrows(IllegalArgumentException.class, () -> create.execute("room-1", black));
        assertSame(ready, get.execute("room-1"));
    }

    @Test
    void missingRoomsFailWithoutBeingCreated() {
        assertThrows(IllegalArgumentException.class, () -> get.execute("missing"));
        assertThrows(IllegalArgumentException.class, () -> join.execute("missing", black));
        assertThrows(IllegalArgumentException.class, () -> move.execute("missing", white, first));
        assertTrue(repository.findByCode("missing").isEmpty());
    }

    @Test
    void repeatedIdentityAndThirdPlayerAreRejected() {
        Room waiting = create.execute("room-1", white);
        assertThrows(IllegalArgumentException.class,
                () -> join.execute("room-1", new User("white", "Alias")));
        assertSame(waiting, get.execute("room-1"));
        Room ready = join.execute("room-1", black);
        assertThrows(IllegalArgumentException.class,
                () -> join.execute("room-1", new User("third", "Eva")));
        assertSame(ready, get.execute("room-1"));
    }

    @Test
    void movingBeforeSecondPlayerJoinsDoesNotChangeStoredRoom() {
        Room waiting = create.execute("room-1", white);
        assertThrows(IllegalArgumentException.class, () -> move.execute("room-1", white, first));
        assertSame(waiting, get.execute("room-1"));
    }

    @Test
    void failedMovesDoNotChangeStoredStateOrHistory() {
        create.execute("room-1", white);
        Room ready = join.execute("room-1", black);
        assertThrows(IllegalArgumentException.class, () -> move.execute("room-1", black, second));
        assertThrows(IllegalArgumentException.class,
                () -> move.execute("room-1", new User("outsider", "Eva"), first));
        assertThrows(IllegalArgumentException.class,
                () -> move.execute("room-1", white, new Move(at("a3"), at("a4"))));
        assertThrows(IllegalArgumentException.class,
                () -> move.execute("room-1", white, new Move(at("a1"), at("a2"))));
        assertSame(ready, get.execute("room-1"));
        Room afterWhite = move.execute("room-1", white, first);
        assertThrows(IllegalArgumentException.class, () -> move.execute("room-1", white, first));
        assertSame(afterWhite, get.execute("room-1"));
        assertEquals(List.of(first), get.execute("room-1").game().moves());
    }

    @Test
    void invalidInputsDoNotChangeStoredRoom() {
        assertThrows(IllegalArgumentException.class, () -> create.execute("", white));
        assertThrows(IllegalArgumentException.class, () -> create.execute("room-1", null));
        assertTrue(repository.findByCode("room-1").isEmpty());
        create.execute("room-1", white);
        Room ready = join.execute("room-1", black);
        assertThrows(IllegalArgumentException.class, () -> join.execute("room-1", null));
        assertThrows(IllegalArgumentException.class, () -> move.execute("room-1", null, first));
        assertThrows(IllegalArgumentException.class, () -> move.execute("room-1", white, null));
        assertSame(ready, get.execute("room-1"));
    }

    @Test
    void repositoryKeepsRoomsIndependentAndReplacesOnlyMatchingCode() {
        Room firstRoom = Room.create("one", white);
        Room secondRoom = Room.create("two", black);
        repository.save(firstRoom);
        repository.save(secondRoom);
        Room joined = firstRoom.join(black);
        repository.save(joined);
        assertSame(joined, repository.findByCode("one").orElseThrow());
        assertSame(secondRoom, repository.findByCode("two").orElseThrow());
        assertNull(firstRoom.black());
        assertTrue(new InMemoryRoomRepository().findByCode("one").isEmpty());
    }

    @Test
    void repositoryRejectsInvalidCodeAndNullRoom() {
        assertThrows(IllegalArgumentException.class, () -> repository.findByCode(null));
        assertThrows(IllegalArgumentException.class, () -> repository.findByCode(""));
        assertThrows(IllegalArgumentException.class, () -> repository.findByCode(" \t"));
        assertThrows(IllegalArgumentException.class, () -> repository.save(null));
    }

    @Test
    void useCasesAcceptAnotherRepositoryWithoutChanges() {
        SingleRoomRepository replacement = new SingleRoomRepository();
        new CreateRoomUseCase(replacement).execute("room-1", white);
        new JoinRoomUseCase(replacement).execute("room-1", black);
        new MoveUseCase(replacement).execute("room-1", white, first);
        Room stored = new GetRoomUseCase(replacement).execute("room-1");
        assertEquals(List.of(first), stored.game().moves());
        assertEquals(3, replacement.saves);
        assertThrows(IllegalArgumentException.class,
                () -> new MoveUseCase(replacement).execute("room-1", white, first));
        assertEquals(3, replacement.saves);
    }

    @Test
    void enteringReopensMembershipWithoutSavingOrChangingHistory() {
        SingleRoomRepository replacement = new SingleRoomRepository();
        GetRoomUseCase lookup = new GetRoomUseCase(replacement);
        EnterRoomUseCase enter = new EnterRoomUseCase(lookup, new JoinRoomUseCase(replacement));
        Room waiting = new CreateRoomUseCase(replacement).execute("room-1", white);
        assertSame(waiting, enter.execute("room-1", new User(white.id(), "Alias")));
        assertEquals(1, replacement.saves);
        Room ready = enter.execute("room-1", black);
        assertEquals(black, ready.black());
        assertEquals(2, replacement.saves);
        Room moved = new MoveUseCase(replacement).execute("room-1", white, first);
        assertSame(moved, enter.execute("room-1", white));
        assertSame(moved, enter.execute("room-1", new User(black.id(), "Alias")));
        assertEquals(List.of(first), moved.game().moves());
        assertThrows(IllegalArgumentException.class,
                () -> enter.execute("room-1", new User("third", "Eva")));
        assertSame(moved, lookup.execute("room-1"));
        assertEquals(3, replacement.saves);
    }

    @Test
    void enteringMissingRoomDoesNotCreateOrSaveAnything() {
        SingleRoomRepository replacement = new SingleRoomRepository();
        EnterRoomUseCase enter = new EnterRoomUseCase(new GetRoomUseCase(replacement), new JoinRoomUseCase(replacement));
        assertThrows(IllegalArgumentException.class, () -> enter.execute("missing", black));
        assertTrue(replacement.findByCode("missing").isEmpty());
        assertEquals(0, replacement.saves);
    }

    private static class SingleRoomRepository implements RoomRepository {
        private Room room;
        private int saves;

        @Override
        public Optional<Room> findByCode(String code) {
            if (code == null || code.isBlank())
                throw new IllegalArgumentException("Room code cannot be empty");
            return Optional.ofNullable(room).filter(value -> value.code().equals(code));
        }

        @Override
        public void save(Room room) {
            if (room == null) throw new IllegalArgumentException("Room cannot be null");
            this.room = room;
            saves++;
        }
    }
}
