package software.ulpgc.chess.application.domain.model;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static software.ulpgc.chess.application.domain.model.Piece.*;
import static software.ulpgc.chess.application.domain.model.Square.at;

class EnPassantTest {
    @Test void whiteCanCaptureAfterADoubleStepAndReplayTheCapture() {
        Game before = play("e2e4", "a7a6", "e4e5", "d7d5");
        assertEquals(at("d6"), before.state().enPassantTarget());
        State withoutTarget = new State(before.state().board(), before.turn());
        assertEquals(before, Game.restore(before.id(), withoutTarget, before.moves()));

        Game after = before.move(move("e5d6"));

        assertEquals(WhitePawn, after.pieceAt(at("d6")));
        assertNull(after.pieceAt(at("e5")));
        assertNull(after.pieceAt(at("d5")));
        assertNull(after.state().enPassantTarget());
        assertEquals(BlackPawn, before.pieceAt(at("d5")));
        assertEquals(after, Game.restore(after.id(), after.state(), after.moves()));
    }

    @Test void blackCanCaptureAfterAWhiteDoubleStep() {
        Game game = play("a2a3", "e7e5", "a3a4", "e5e4", "d2d4", "e4d3");

        assertEquals(BlackPawn, game.pieceAt(at("d3")));
        assertNull(game.pieceAt(at("d4")));
        assertEquals(Color.White, game.turn());
    }

    @Test void theCaptureExpiresAfterTheImmediateReply() {
        Game game = play("e2e4", "a7a6", "e4e5", "d7d5", "g1f3", "h7h6");

        assertThrows(IllegalArgumentException.class, () -> game.move(move("e5d6")));
        assertEquals(BlackPawn, game.pieceAt(at("d5")));
    }

    @Test void aSinglePawnStepDoesNotAllowEnPassant() {
        Board board = new Board(Map.of(at("a1"), WhiteKing, at("h8"), BlackKing,
                at("e5"), WhitePawn, at("d6"), BlackPawn));
        State state = new State(board, Color.Black).move(move("d6d5"));

        assertNull(state.enPassantTarget());
        assertThrows(IllegalArgumentException.class, () -> state.move(move("e5d6")));
    }

    @Test void enPassantCannotExposeTheMovingKing() {
        Board board = new Board(Map.of(at("h5"), WhiteKing, at("e8"), BlackKing, at("a5"), BlackRook,
                at("g5"), WhitePawn, at("f7"), BlackPawn));
        State state = new State(board, Color.Black).move(move("f7f5"));

        assertThrows(IllegalArgumentException.class, () -> state.move(move("g5f6")));
    }

    @Test void aLegalEnPassantCapturePreventsStalemate() {
        Board board = new Board(Map.of(at("c6"), WhiteKing, at("b6"), WhiteQueen, at("a8"), BlackKing,
                at("d4"), BlackPawn, at("d3"), WhiteBishop, at("e2"), WhitePawn));
        State state = new State(board, Color.White).move(move("e2e4"));

        assertTrue(new State(state.board(), Color.Black).isStalemate());
        assertFalse(state.isStalemate());
        assertEquals(BlackPawn, state.move(move("d4e3")).pieceAt(at("e3")));
    }

    @Test void anEnPassantTargetMustMatchThePositionAndTurn() {
        assertThrows(IllegalArgumentException.class, () -> new State(Board.initial(), Color.White, at("d6")));
        State played = State.initial().move(move("d2d4"));
        assertThrows(IllegalArgumentException.class, () -> new State(played.board(), Color.White, at("d3")));
    }

    private Game play(String... moves) {
        Game game = Game.initial("en-passant");
        for (String notation : moves) game = game.move(move(notation));
        return game;
    }

    private Move move(String notation) {
        return new Move(at(notation.substring(0, 2)), at(notation.substring(2)));
    }
}
