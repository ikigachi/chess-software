package software.ulpgc.chess.application.domain.model;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static software.ulpgc.chess.application.domain.model.Square.at;

class DomainInvariantsTest {
    private final Move first = new Move(at("e2"), at("e4"));

    @Test
    void incompleteSquaresCannotBeConstructed() {
        assertThrows(IllegalArgumentException.class, () -> new Square(null, null));
        assertThrows(IllegalArgumentException.class, () -> new Square(File.A, null));
        assertThrows(IllegalArgumentException.class, () -> new Square(null, Rank.R1));
        assertThrows(IllegalArgumentException.class, () -> at("i1"));
        assertThrows(IllegalArgumentException.class, () -> at(0, 1));
    }

    @Test
    void coordinatesRoundTripForEverySquare() {
        for (int file = 1; file <= 8; file++) {
            for (int rank = 1; rank <= 8; rank++) {
                Square square = at(file, rank);
                assertEquals(square, at(square.coordinate()));
            }
        }
        assertEquals("a1", at(1, 1).coordinate());
        assertEquals("h8", at(8, 8).coordinate());
    }

    @Test
    void gameRestorationRejectsInconsistentOrInvalidHistory() {
        State played = State.initial().move(first);
        assertThrows(IllegalArgumentException.class,
                () -> Game.restore("g", State.initial(), List.of(first)));
        assertThrows(IllegalArgumentException.class,
                () -> Game.restore("g", played, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> Game.restore("g", new State(played.board(), Color.White), List.of(first)));
        assertThrows(IllegalArgumentException.class,
                () -> Game.restore("g", State.initial(), List.of(new Move(at("a3"), at("a4")))));
    }

    @Test
    void validRestorationAndMovesPreserveImmutableValues() {
        Game initial = Game.initial("g");
        Game played = initial.move(first);
        List<Move> history = new ArrayList<>(List.of(first));
        Game restored = Game.restore("g", played.state(), history);
        history.clear();
        assertEquals(played, restored);
        assertEquals(played.hashCode(), restored.hashCode());
        assertEquals(List.of(first), restored.moves());
        assertThrows(UnsupportedOperationException.class, () -> restored.moves().clear());
        assertTrue(initial.moves().isEmpty());
        assertEquals(State.initial(), initial.state());
        assertEquals(Piece.WhitePawn, restored.pieceAt(at("e4")));
        assertNull(restored.pieceAt(at("e2")));
    }

    @Test
    void membershipOwnershipAndTurnQueriesAgreeWithMovementRules() {
        User white = new User("white", "Alice");
        User black = new User("black", "Bob");
        User stranger = new User("other", "Alice");
        Room waiting = Room.create("room", white);
        assertTrue(waiting.hasPlayer(new User("white", "Renamed")));
        assertFalse(waiting.hasPlayer(stranger));
        assertFalse(waiting.hasPlayer(null));
        assertThrows(IllegalArgumentException.class, () -> waiting.validateTurn(white));
        Room ready = waiting.join(black);
        assertDoesNotThrow(() -> ready.validateTurn(white));
        assertThrows(IllegalArgumentException.class, () -> ready.validateTurn(black));
        assertThrows(IllegalArgumentException.class, () -> ready.validateTurn(stranger));
        assertTrue(ready.ownsPiece(white, at("e2")));
        assertFalse(ready.ownsPiece(white, at("e7")));
        assertFalse(ready.ownsPiece(white, at("e4")));
        assertThrows(IllegalArgumentException.class, () -> ready.ownsPiece(stranger, at("e2")));
        Room played = ready.move(white, first);
        assertDoesNotThrow(() -> played.validateTurn(black));
        assertEquals(Color.White, ready.game().turn());
    }
}
