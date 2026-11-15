package software.ulpgc.chess.application.domain.model;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static software.ulpgc.chess.application.domain.model.Piece.*;
import static software.ulpgc.chess.application.domain.model.Square.at;

class KingSafetyTest {
    @Test void blockingPieceCanMoveAlongThePinButCannotExposeItsKing() {
        State state = new State(new Board(Map.of(at("e1"), WhiteKing, at("e2"), WhiteRook,
                at("e8"), BlackRook, at("h8"), BlackKing)), Color.White);

        assertThrows(IllegalArgumentException.class, () -> state.move(move("e2", "d2")));
        assertEquals(WhiteRook, state.move(move("e2", "e3")).pieceAt(at("e3")));
        assertEquals(WhiteRook, state.move(move("e2", "e8")).pieceAt(at("e8")));
    }

    @Test void pawnsAttackDiagonallyInTheirOwnDirection() {
        State white = new State(new Board(Map.of(at("d3"), WhiteKing, at("d5"), BlackPawn,
                at("h8"), BlackKing)), Color.White);
        State black = new State(new Board(Map.of(at("d6"), BlackKing, at("d4"), WhitePawn,
                at("h1"), WhiteKing)), Color.Black);

        assertThrows(IllegalArgumentException.class, () -> white.move(move("d3", "c4")));
        assertThrows(IllegalArgumentException.class, () -> black.move(move("d6", "c5")));
        assertEquals(WhiteKing, white.move(move("d3", "d4")).pieceAt(at("d4")));
        assertEquals(BlackKing, black.move(move("d6", "d5")).pieceAt(at("d5")));
    }

    @Test void kingsCannotMoveNextToEachOther() {
        State state = new State(new Board(Map.of(at("e1"), WhiteKing, at("e3"), BlackKing)), Color.White);

        assertThrows(IllegalArgumentException.class, () -> state.move(move("e1", "e2")));
    }

    @Test void aPinnedPieceStillAttacksSquaresTheKingCannotEnter() {
        State state = new State(new Board(Map.of(at("f4"), WhiteKing, at("e1"), WhiteRook,
                at("e7"), BlackKnight, at("e8"), BlackKing)), Color.White);

        assertThrows(IllegalArgumentException.class, () -> state.move(move("f4", "f5")));
    }

    @Test void theOpposingKingCannotBeCaptured() {
        Board board = new Board(Map.of(at("a1"), WhiteRook, at("a8"), BlackKing, at("h1"), WhiteKing));

        assertThrows(IllegalArgumentException.class, () -> board.move(at("a1"), at("a8")));
    }

    private Move move(String from, String to) {
        return new Move(at(from), at(to));
    }
}
