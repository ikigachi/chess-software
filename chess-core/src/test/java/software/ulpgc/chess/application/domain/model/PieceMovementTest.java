package software.ulpgc.chess.application.domain.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static software.ulpgc.chess.application.domain.model.Piece.*;
import static software.ulpgc.chess.application.domain.model.Square.at;

class PieceMovementTest {
    @Test void rooksMoveOnlyAlongFilesAndRanks() {
        for (Piece piece : List.of(WhiteRook, BlackRook)) {
            Board board = board(piece, "d4");
            for (String to : List.of("d1", "d8", "a4", "h4")) assertMove(board, "d4", to, piece);
            for (String to : List.of("e5", "b6", "f5")) assertRejected(board, "d4", to);
        }
    }

    @Test void bishopsMoveOnlyAlongDiagonals() {
        for (Piece piece : List.of(WhiteBishop, BlackBishop)) {
            Board board = board(piece, "d4");
            for (String to : List.of("a1", "g1", "a7", "h8")) assertMove(board, "d4", to, piece);
            for (String to : List.of("d8", "h4", "f5")) assertRejected(board, "d4", to);
        }
    }

    @Test void queensCombineRookAndBishopMovement() {
        for (Piece piece : List.of(WhiteQueen, BlackQueen)) {
            Board board = board(piece, "d4");
            for (String to : List.of("d1", "d8", "a4", "h4", "a1", "g1", "a7", "h8"))
                assertMove(board, "d4", to, piece);
            for (String to : List.of("e6", "f5", "b7")) assertRejected(board, "d4", to);
        }
    }

    @Test void knightsMoveInAnLAndCanJump() {
        for (Piece piece : List.of(WhiteKnight, BlackKnight)) {
            Board board = board(piece, "d4");
            for (String to : List.of("b3", "b5", "c2", "c6", "e2", "e6", "f3", "f5"))
                assertMove(board, "d4", to, piece);
            for (String to : List.of("d5", "d6", "e5", "g4")) assertRejected(board, "d4", to);
        }
        assertMove(Board.initial(), "g1", "f3", WhiteKnight);
        assertMove(Board.initial(), "b8", "c6", BlackKnight);
        assertRejected(Board.initial(), "g1", "e2");
    }

    @Test void kingsMoveOneSquareInAnyDirection() {
        for (Piece piece : List.of(WhiteKing, BlackKing)) {
            Board board = board(piece, "d4");
            for (String to : List.of("c3", "c4", "c5", "d3", "d5", "e3", "e4", "e5"))
                assertMove(board, "d4", to, piece);
            for (String to : List.of("d6", "f4", "b2")) assertRejected(board, "d4", to);
        }
        assertRejected(board(WhiteKing, "e1"), "e1", "g1");
    }

    @Test void pawnsAdvanceInTheirOwnDirectionWithAnInitialDoubleStep() {
        Board white = board(WhitePawn, "d2");
        assertMove(white, "d2", "d3", WhitePawn);
        Board advancedWhite = white.move(at("d2"), at("d4"));
        assertMove(advancedWhite, "d4", "d5", WhitePawn);
        for (String to : List.of("d3", "d6", "c4", "c5", "e5")) assertRejected(advancedWhite, "d4", to);
        assertRejected(white, "d2", "d5");
        Board black = board(BlackPawn, "d7");
        assertMove(black, "d7", "d6", BlackPawn);
        Board advancedBlack = black.move(at("d7"), at("d5"));
        assertMove(advancedBlack, "d5", "d4", BlackPawn);
        for (String to : List.of("d6", "d3", "c5", "c4", "e4")) assertRejected(advancedBlack, "d5", to);
        assertRejected(black, "d7", "d4");
    }

    @Test void pawnsCaptureOnlyOneSquareDiagonallyForward() {
        for (String to : List.of("c5", "e5"))
            assertMove(new Board(Map.of(at("d4"), WhitePawn, at(to), BlackRook)), "d4", to, WhitePawn);
        for (String to : List.of("c4", "e4"))
            assertMove(new Board(Map.of(at("d5"), BlackPawn, at(to), WhiteRook)), "d5", to, BlackPawn);
        for (String to : List.of("d5", "c3", "e3", "b6"))
            assertRejected(new Board(Map.of(at("d4"), WhitePawn, at(to), BlackRook)), "d4", to);
        for (String to : List.of("d4", "c6", "e6", "b3"))
            assertRejected(new Board(Map.of(at("d5"), BlackPawn, at(to), WhiteRook)), "d5", to);
        assertRejected(new Board(Map.of(at("d2"), WhitePawn, at("d4"), BlackRook)), "d2", "d4");
        assertRejected(new Board(Map.of(at("d7"), BlackPawn, at("d5"), WhiteRook)), "d7", "d5");
    }

    @Test void slidingPiecesCannotCrossFriendlyOrEnemyPieces() {
        for (Piece blocker : List.of(WhitePawn, BlackPawn)) {
            assertRejected(new Board(Map.of(at("d4"), WhiteRook, at("d5"), blocker)), "d4", "d8");
            assertRejected(new Board(Map.of(at("d4"), BlackBishop, at("c3"), blocker)), "d4", "a1");
            assertRejected(new Board(Map.of(at("d4"), WhiteQueen, at("e4"), blocker)), "d4", "h4");
            assertRejected(new Board(Map.of(at("d4"), BlackQueen, at("e5"), blocker)), "d4", "h8");
        }
        assertRejected(Board.initial(), "a1", "a4");
        assertRejected(Board.initial(), "c1", "h6");
        assertRejected(Board.initial(), "d8", "d5");
    }

    @Test void pawnDoubleStepsCannotCrossAnOccupiedSquare() {
        for (Piece blocker : List.of(WhiteKnight, BlackKnight)) {
            assertRejected(new Board(Map.of(at("d2"), WhitePawn, at("d3"), blocker)), "d2", "d4");
            assertRejected(new Board(Map.of(at("d7"), BlackPawn, at("d6"), blocker)), "d7", "d5");
        }
    }

    @Test void capturesReplaceOnlyTheEnemyAtTheDestination() {
        for (Piece piece : List.of(WhiteRook, WhiteBishop, WhiteQueen, WhiteKnight, WhiteKing)) {
            String to = piece.isKnight() ? "f5" : piece.isRook() ? "d5" : "e5";
            Board original = new Board(Map.of(at("d4"), piece, at(to), BlackPawn, at("a8"), BlackKing));
            Board moved = original.move(at("d4"), at(to));
            assertEquals(2, moved.pieces().size());
            assertEquals(piece, moved.pieceAt(at(to)));
            assertEquals(BlackKing, moved.pieceAt(at("a8")));
            assertEquals(3, original.pieces().size());
            assertRejected(new Board(Map.of(at("d4"), piece, at(to), WhitePawn)), "d4", to);
        }
    }

    @Test void rejectedMovementsPreserveTurnAndHistory() {
        Game original = Game.initial("game");
        for (Move move : List.of(new Move(at("e2"), at("e5")), new Move(at("a1"), at("a4")),
                new Move(at("g1"), at("g3")))) {
            assertThrows(IllegalArgumentException.class, () -> original.move(move));
            assertThrows(IllegalArgumentException.class, () -> Game.restore("game", State.initial(), List.of(move)));
        }
        assertEquals(Color.White, original.turn());
        assertTrue(original.moves().isEmpty());
        assertEquals(Board.initial(), original.state().board());
    }

    private Board board(Piece piece, String square) {
        return new Board(Map.of(at(square), piece));
    }

    private void assertMove(Board board, String from, String to, Piece piece) {
        Board moved = board.move(at(from), at(to));
        assertEquals(piece, moved.pieceAt(at(to)), from + " to " + to);
        assertNull(moved.pieceAt(at(from)));
        assertEquals(piece, board.pieceAt(at(from)));
    }

    private void assertRejected(Board board, String from, String to) {
        Map<Square, Piece> original = Map.copyOf(board.pieces());
        assertThrows(IllegalArgumentException.class, () -> board.move(at(from), at(to)), from + " to " + to);
        assertEquals(original, board.pieces());
    }
}
