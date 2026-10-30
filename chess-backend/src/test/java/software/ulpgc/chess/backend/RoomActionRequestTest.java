package software.ulpgc.chess.backend;

import org.junit.jupiter.api.Test;
import software.ulpgc.chess.application.domain.model.Move;
import software.ulpgc.chess.backend.infrastructure.http.RoomActionRequest;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static software.ulpgc.chess.application.domain.model.Square.at;

class RoomActionRequestTest {
    @Test void normalizesFormValuesAndBuildsDomainMovesWithoutHttp() {
        var create = RoomActionRequest.from(Map.of("action", "create", "name", " Zoë "));
        assertEquals("Zoë", create.name());
        var join = RoomActionRequest.from(Map.of("action", "join", "name", " Bob ", "code", " ab12cd "));
        assertEquals("Bob", join.name());
        assertEquals("AB12CD", join.code());
        var move = RoomActionRequest.from(Map.of("action", "move", "from", "e2", "to", "e4"));
        assertEquals(new Move(at("e2"), at("e4")), move.move());
    }

    @Test void rejectsIncompleteOrInvalidActionInputs() {
        assertThrows(IllegalArgumentException.class, () -> RoomActionRequest.from(Map.of()));
        assertThrows(IllegalArgumentException.class, () -> RoomActionRequest.from(Map.of("action", "unknown")));
        assertThrows(IllegalArgumentException.class, () -> RoomActionRequest.from(Map.of("action", "create", "name", " ")));
        assertThrows(IllegalArgumentException.class, () -> RoomActionRequest.from(Map.of("action", "create", "name", "a".repeat(41))));
        assertThrows(IllegalArgumentException.class, () -> RoomActionRequest.from(Map.of("action", "join", "name", "Bob")));
        assertThrows(IllegalArgumentException.class, () -> RoomActionRequest.from(Map.of("action", "join", "name", "Bob", "code", "a".repeat(31))));
        assertThrows(IllegalArgumentException.class, () -> RoomActionRequest.from(Map.of("action", "move", "from", "z9", "to", "e4")));
        assertThrows(IllegalArgumentException.class, () -> RoomActionRequest.from(Map.of("action", "move", "from", "e2")));
    }

    @Test void validatesOnlyFieldsUsedByTheAction() {
        assertEquals("refresh", RoomActionRequest.from(Map.of("action", "refresh")).action());
        assertEquals("leave", RoomActionRequest.from(Map.of("action", "leave", "name", " ", "code", " ")).action());
        assertEquals("create", RoomActionRequest.from(Map.of("action", "create", "name", "Alice", "code", " ")).action());
        assertEquals("move", RoomActionRequest.from(Map.of("action", "move", "from", "e2", "to", "e4", "name", " ", "code", " ")).action());
    }
}
