package software.ulpgc.chess.application.domain.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static software.ulpgc.chess.application.domain.model.Piece.*;
import static software.ulpgc.chess.application.domain.model.Square.at;

class PawnPromotionTest {
    @Test void aPawnCanPromoteToAnyOfTheFourPiecesOfItsColor() {
        Board board = new Board(Map.of(at("e2"), WhiteKing, at("e7"), BlackKing,
                at("a7"), WhitePawn, at("h2"), BlackPawn));
        State white = new State(board, Color.White);
        State black = new State(board, Color.Black);
        Map<Promotion, Piece> whitePieces = Map.of(Promotion.Queen, WhiteQueen, Promotion.Rook, WhiteRook,
                Promotion.Bishop, WhiteBishop, Promotion.Knight, WhiteKnight);
        Map<Promotion, Piece> blackPieces = Map.of(Promotion.Queen, BlackQueen, Promotion.Rook, BlackRook,
                Promotion.Bishop, BlackBishop, Promotion.Knight, BlackKnight);

        for (Promotion choice : Promotion.values()) {
            assertEquals(whitePieces.get(choice), white.move(new Move(at("a7"), at("a8"), choice)).pieceAt(at("a8")));
            assertEquals(blackPieces.get(choice), black.move(new Move(at("h2"), at("h1"), choice)).pieceAt(at("h1")));
        }
    }

    @Test void aMoveWithoutAChoicePromotesToAQueenEvenWhenCapturing() {
        Board board = new Board(Map.of(at("e2"), WhiteKing, at("e7"), BlackKing,
                at("a7"), WhitePawn, at("b8"), BlackRook));
        State promoted = new State(board, Color.White).move(new Move(at("a7"), at("b8")));

        assertEquals(WhiteQueen, promoted.pieceAt(at("b8")));
        assertNull(promoted.pieceAt(at("a7")));
        assertEquals(Color.Black, promoted.turn());
        assertEquals(BlackRook, board.pieceAt(at("b8")));
    }

    @Test void aChoiceIsOnlyAllowedForAPawnReachingItsLastRank() {
        State state = State.initial();

        assertThrows(IllegalArgumentException.class, () -> state.move(new Move(at("e2"), at("e4"), Promotion.Queen)));
        assertThrows(IllegalArgumentException.class, () -> state.move(new Move(at("g1"), at("f3"), Promotion.Knight)));
        assertEquals(Color.White, state.turn());
        assertEquals(WhitePawn, state.pieceAt(at("e2")));
    }

    @Test void promotingCannotExposeTheMovingPlayersKing() {
        Board board = new Board(Map.of(at("a1"), WhiteKing, at("e7"), BlackKing,
                at("a7"), WhitePawn, at("a8"), BlackRook, at("b8"), BlackKnight));
        State state = new State(board, Color.White);

        assertThrows(IllegalArgumentException.class, () -> state.move(new Move(at("a7"), at("b8"), Promotion.Bishop)));
    }

    @Test void theGameHistoryRecordsTheChoiceForReplay() {
        Game game = Game.initial("promotion");
        for (String squares : List.of("a2a4", "h7h5", "a4a5", "h5h4", "a5a6", "h4h3", "a6b7", "h3g2")) {
            game = game.move(new Move(at(squares.substring(0, 2)), at(squares.substring(2))));
        }
        Move promotion = new Move(at("b7"), at("a8"), Promotion.Knight);
        Game promoted = game.move(promotion);

        assertEquals(WhiteKnight, promoted.pieceAt(at("a8")));
        assertEquals(promotion, promoted.moves().getLast());
        assertEquals(promoted, Game.restore(promoted.id(), promoted.state(), promoted.moves()));
    }
}
