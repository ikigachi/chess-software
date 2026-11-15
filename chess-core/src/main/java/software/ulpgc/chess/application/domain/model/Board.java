package software.ulpgc.chess.application.domain.model;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import static software.ulpgc.chess.application.domain.model.Piece.*;
import static software.ulpgc.chess.application.domain.model.Square.at;

public final class Board {
    private final Map<Square, Piece> pieces;

    public Board(Map<Square, Piece> pieces) {
        this.pieces = Map.copyOf(pieces);
    }

    public static Board initial() {
        return new Board(initialMap());
    }

    public Board move(Square from, Square to) {
        return move(from, to, null);
    }

    Board move(Square from, Square to, Promotion promotion) {
        validateSquare(from);
        validateSquare(to);
        String rejection = moveRejection(from, to);
        if (rejection != null) throw new IllegalArgumentException(rejection);
        if (promotion != null && !pieceAt(from).promotesOn(to.rank()))
            throw new IllegalArgumentException("Only a pawn reaching the last rank can promote.");
        return withPieceMoved(from, to, promotion);
    }

    private String moveRejection(Square from, Square to) {
        if (from.equals(to))
            return "Origin and destination must be different";
        Piece piece = pieceAt(from);
        if (piece == null)
            return "No piece at origin";
        Piece target = pieceAt(to);
        if (target != null && target.color() == piece.color())
            return "Cannot capture a piece of the same color";
        if (target != null && target.isKing())
            return "The king cannot be captured.";
        if (!piece.canMove(from, to, target != null))
            return "Invalid movement for this piece";
        if (!hasClearPath(piece, from, to))
            return "A piece blocks the path";
        return null;
    }

    private Board withPieceMoved(Square from, Square to, Promotion promotion) {
        Map<Square, Piece> movedPieces = new HashMap<>(pieces);
        Piece piece = movedPieces.remove(from);
        if (piece.promotesOn(to.rank())) {
            Promotion choice = promotion == null ? Promotion.Queen : promotion;
            piece = choice.pieceOf(piece.color());
        }
        movedPieces.put(to, piece);
        return new Board(movedPieces);
    }

    boolean hasLegalMove(Color color) {
        for (var entry : pieces.entrySet()) {
            if (entry.getValue().color() == color && hasLegalMoveFrom(entry.getKey(), color)) return true;
        }
        return false;
    }

    private boolean hasLegalMoveFrom(Square from, Color color) {
        for (File file : File.values()) {
            for (Rank rank : Rank.values()) {
                Square to = new Square(file, rank);
                if (moveRejection(from, to) == null && !withPieceMoved(from, to, null).isInCheck(color))
                    return true;
            }
        }
        return false;
    }

    boolean hasKing(Color color) {
        return pieces.containsValue(kingOf(color));
    }

    boolean isInCheck(Color color) {
        Piece king = kingOf(color);
        for (var entry : pieces.entrySet()) {
            if (entry.getValue() == king) return isAttacked(entry.getKey(), color);
        }
        return false;
    }

    private static Piece kingOf(Color color) {
        return color == Color.White ? WhiteKing : BlackKing;
    }

    private boolean isAttacked(Square square, Color defendingColor) {
        for (var entry : pieces.entrySet()) {
            Piece piece = entry.getValue();
            Square from = entry.getKey();
            if (piece.color() != defendingColor && piece.canMove(from, square, true)
                    && hasClearPath(piece, from, square)) return true;
        }
        return false;
    }

    private boolean hasClearPath(Piece piece, Square from, Square to) {
        return piece.isKnight() || pathIsClear(from, to);
    }

    private boolean pathIsClear(Square from, Square to) {
        int fileDifference = to.file().symbol() - from.file().symbol();
        int rankDifference = to.rank().symbol() - from.rank().symbol();
        int distance = Math.max(Math.abs(fileDifference), Math.abs(rankDifference));
        for (int step = 1; step < distance; step++) {
            Square square = Square.at(from.file().symbol() - 'a' + 1 + step * Integer.signum(fileDifference),
                    from.rank().symbol() + step * Integer.signum(rankDifference));
            if (pieceAt(square) != null) return false;
        }
        return true;
    }

    private static void validateSquare(Square square) {
        if (square == null)
            throw new IllegalArgumentException("Move squares cannot be null");
    }

    private static Map<Square, Piece> initialMap() {
        return Map.ofEntries(
                Map.entry(at("a1"), WhiteRook),
                Map.entry(at("b1"), WhiteKnight),
                Map.entry(at("c1"), WhiteBishop),
                Map.entry(at("d1"), WhiteQueen),
                Map.entry(at("e1"), WhiteKing),
                Map.entry(at("f1"), WhiteBishop),
                Map.entry(at("g1"), WhiteKnight),
                Map.entry(at("h1"), WhiteRook),
                Map.entry(at("a2"), WhitePawn),
                Map.entry(at("b2"), WhitePawn),
                Map.entry(at("c2"), WhitePawn),
                Map.entry(at("d2"), WhitePawn),
                Map.entry(at("e2"), WhitePawn),
                Map.entry(at("f2"), WhitePawn),
                Map.entry(at("g2"), WhitePawn),
                Map.entry(at("h2"), WhitePawn),
                Map.entry(at("a7"), BlackPawn),
                Map.entry(at("b7"), BlackPawn),
                Map.entry(at("c7"), BlackPawn),
                Map.entry(at("d7"), BlackPawn),
                Map.entry(at("e7"), BlackPawn),
                Map.entry(at("f7"), BlackPawn),
                Map.entry(at("g7"), BlackPawn),
                Map.entry(at("h7"), BlackPawn),
                Map.entry(at("a8"), BlackRook),
                Map.entry(at("b8"), BlackKnight),
                Map.entry(at("c8"), BlackBishop),
                Map.entry(at("d8"), BlackQueen),
                Map.entry(at("e8"), BlackKing),
                Map.entry(at("f8"), BlackBishop),
                Map.entry(at("g8"), BlackKnight),
                Map.entry(at("h8"), BlackRook)
        );
    }


    Piece pieceAt(File file, Rank rank) {
        return pieceAt(new Square(file, rank));
    }

    Piece pieceAt(String square) {
        return pieceAt(at(square));
    }

    Piece pieceAt(Square square) {
        return pieces.get(square);
    }

    public Map<Square, Piece> pieces() {
        return pieces;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) return true;
        if (obj == null || obj.getClass() != this.getClass()) return false;
        var that = (Board) obj;
        return Objects.equals(this.pieces, that.pieces);
    }

    @Override
    public int hashCode() {
        return Objects.hash(pieces);
    }

    @Override
    public String toString() {
        return "Board[" +
                "pieces=" + pieces + ']';
    }

}
