package software.ulpgc.chess.application.domain.model;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static software.ulpgc.chess.application.domain.model.Piece.*;
import static software.ulpgc.chess.application.domain.model.Square.at;

class CheckmateTest {
    @Test void identifiesFoolsMate() {
        State beforeMate = State.initial()
                .move(move("f2", "f3"))
                .move(move("e7", "e5"))
                .move(move("g2", "g4"));

        assertFalse(beforeMate.isCheckmate());
        assertTrue(beforeMate.move(move("d8", "h4")).isCheckmate());
    }

    @Test void aPieceCanPreventMateByBlockingTheAttack() {
        Board board = new Board(Map.of(at("a1"), WhiteKing, at("c3"), WhiteBishop,
                at("a8"), BlackRook, at("c2"), BlackKing));

        assertTrue(board.isInCheck(Color.White));
        assertFalse(new State(board, Color.White).isCheckmate());
    }

    @Test void theKingCanPreventMateByCapturingTheAttacker() {
        Board board = new Board(Map.of(at("a1"), WhiteKing, at("a2"), BlackRook, at("c2"), BlackKing));

        assertTrue(board.isInCheck(Color.White));
        assertFalse(new State(board, Color.White).isCheckmate());
    }

    @Test void havingNoLegalMoveWithoutCheckIsNotCheckmate() {
        Board board = new Board(Map.of(at("c6"), WhiteKing, at("b6"), WhiteQueen, at("a8"), BlackKing));

        assertFalse(board.hasLegalMove(Color.Black));
        assertFalse(new State(board, Color.Black).isCheckmate());
    }

    private Move move(String from, String to) {
        return new Move(at(from), at(to));
    }
}
