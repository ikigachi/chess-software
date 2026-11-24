package software.ulpgc.chess.matchesservice;

import org.junit.jupiter.api.Test;
import software.ulpgc.chess.application.domain.model.Room;
import software.ulpgc.chess.application.domain.model.User;
import software.ulpgc.chess.matchesservice.application.usecases.GetMatchUseCase;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class GetMatchUseCaseTest {
    @Test void normalizesTheCodeAndUsesTheInjectedReaderWithoutChangingTheRoom() {
        var room = Room.create("ABC123", new User("alice", "Recorded Alice"));
        var queriedCode = new AtomicReference<String>();
        var matches = new GetMatchUseCase(code -> {
            queriedCode.set(code);
            return Optional.of(room);
        });

        assertSame(room, matches.findByCode(" abc123 ").orElseThrow());
        assertEquals("ABC123", queriedCode.get());
        assertTrue(room.game().moves().isEmpty());
        assertNull(room.black());
    }

    @Test void rejectsInvalidCodesBeforeContactingTheReader() {
        var matches = new GetMatchUseCase(code -> { throw new AssertionError("Invalid input reached the reader."); });
        for (String code : new String[] {null, "", "   ", "a".repeat(31)})
            assertThrows(IllegalArgumentException.class, () -> matches.findByCode(code));
    }

    @Test void distinguishesAnAbsentMatchFromAnUnavailableReader() {
        assertTrue(new GetMatchUseCase(code -> Optional.empty()).findByCode("ABC123").isEmpty());
        var failure = new IllegalStateException("Query failed.");
        var matches = new GetMatchUseCase(code -> { throw failure; });
        assertSame(failure, assertThrows(IllegalStateException.class, () -> matches.findByCode("ABC123")));
    }
}
