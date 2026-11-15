package software.ulpgc.chess.application.domain.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static software.ulpgc.chess.application.domain.model.Piece.*;
import static software.ulpgc.chess.application.domain.model.Square.at;

class CastlingTest {
    @Test void eitherColorCanCastleOnEitherSide() {
        assertCastle(Color.White, "e1g1", "h1", "f1", WhiteKing, WhiteRook);
        assertCastle(Color.White, "e1c1", "a1", "d1", WhiteKing, WhiteRook);
        assertCastle(Color.Black, "e8g8", "h8", "f8", BlackKing, BlackRook);
        assertCastle(Color.Black, "e8c8", "a8", "d8", BlackKing, BlackRook);
    }

    @Test void theEntirePathBetweenKingAndRookMustBeClear() {
        Board board = new Board(Map.of(at("e1"), WhiteKing, at("a1"), WhiteRook,
                at("b1"), WhiteKnight, at("h8"), BlackKing));
        State state = new State(board, Color.White, null, Set.of(Castling.WhiteQueenside));

        assertThrows(IllegalArgumentException.class, () -> state.move(move("e1c1")));
        assertTrue(state.castlingRights().contains(Castling.WhiteQueenside));
    }

    @Test void theKingCannotStartCrossOrFinishInCheck() {
        for (String attacker : List.of("e8", "f8", "g8")) {
            Board board = new Board(Map.of(at("e1"), WhiteKing, at("h1"), WhiteRook,
                    at("a8"), BlackKing, at(attacker), BlackRook));
            State state = new State(board, Color.White, null, Set.of(Castling.WhiteKingside));

            assertThrows(IllegalArgumentException.class, () -> state.move(move("e1g1")), attacker);
        }
    }

    @Test void theRookMayCrossAnAttackedSquare() {
        Board board = new Board(Map.of(at("e1"), WhiteKing, at("a1"), WhiteRook,
                at("b8"), BlackRook, at("h8"), BlackKing));
        State state = new State(board, Color.White, null, Set.of(Castling.WhiteQueenside));

        assertEquals(WhiteRook, state.move(move("e1c1")).pieceAt(at("d1")));
    }

    @Test void movingARookRemovesOnlyItsOwnRightEvenIfItReturns() {
        State state = clearBoard(Color.White).move(move("h1h2")).move(move("a8a7"))
                .move(move("h2h1")).move(move("a7a8"));

        assertThrows(IllegalArgumentException.class, () -> state.move(move("e1g1")));
        assertDoesNotThrow(() -> state.move(move("e1c1")));
    }

    @Test void movingTheKingRemovesBothRightsEvenIfItReturns() {
        State state = clearBoard(Color.White).move(move("e1f1")).move(move("e8f8"))
                .move(move("f1e1")).move(move("f8e8"));

        assertThrows(IllegalArgumentException.class, () -> state.move(move("e1g1")));
        assertThrows(IllegalArgumentException.class, () -> state.move(move("e1c1")));
    }

    @Test void replacingACapturedRookDoesNotRestoreItsRight() {
        Board board = new Board(Map.of(at("e1"), WhiteKing, at("a1"), WhiteRook, at("b1"), WhiteRook,
                at("e8"), BlackKing, at("a8"), BlackRook));
        State state = new State(board, Color.Black, null, Set.of(Castling.WhiteQueenside))
                .move(move("a8a1")).move(move("b1a1")).move(move("e8f8"));

        assertEquals(WhiteRook, state.pieceAt(at("a1")));
        assertThrows(IllegalArgumentException.class, () -> state.move(move("e1c1")));
    }

    @Test void replayRestoresACastledPositionAndItsRemainingRights() {
        Game game = Game.initial("castling");
        for (String notation : List.of("e2e4", "e7e5", "g1f3", "g8f6", "f1e2", "f8e7", "e1g1")) {
            game = game.move(move(notation));
        }
        State withoutRights = new State(game.state().board(), game.turn());

        assertEquals(Set.of(Castling.BlackKingside, Castling.BlackQueenside), game.state().castlingRights());
        assertEquals(game, Game.restore(game.id(), withoutRights, game.moves()));
    }

    private void assertCastle(Color color, String notation, String rookFrom, String rookTo, Piece king, Piece rook) {
        State before = clearBoard(color);
        Move move = move(notation);
        State after = before.move(move);

        assertEquals(king, after.pieceAt(move.to()));
        assertEquals(rook, after.pieceAt(at(rookTo)));
        assertNull(after.pieceAt(move.from()));
        assertNull(after.pieceAt(at(rookFrom)));
        assertEquals(rook, before.pieceAt(at(rookFrom)));
    }

    private State clearBoard(Color turn) {
        Board board = new Board(Map.of(at("e1"), WhiteKing, at("a1"), WhiteRook, at("h1"), WhiteRook,
                at("e8"), BlackKing, at("a8"), BlackRook, at("h8"), BlackRook));
        return new State(board, turn, null, Set.of(Castling.values()));
    }

    private Move move(String notation) {
        return new Move(at(notation.substring(0, 2)), at(notation.substring(2)));
    }
}
