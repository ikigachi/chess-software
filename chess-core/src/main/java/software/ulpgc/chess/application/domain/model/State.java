package software.ulpgc.chess.application.domain.model;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

public record State(Board board, Color turn, Square enPassantTarget, Set<Castling> castlingRights) {

    public State(Board board, Color turn) {
        this(board, turn, null);
    }

    public State(Board board, Color turn, Square enPassantTarget) {
        this(board, turn, enPassantTarget, Set.of());
    }

    public State {
        if (board == null || turn == null)
            throw new IllegalArgumentException("State must have a board and turn");
        if (enPassantTarget != null && !validEnPassantTarget(board, turn, enPassantTarget))
            throw new IllegalArgumentException("Invalid en passant target.");
        if (castlingRights == null) throw new IllegalArgumentException("State must have castling rights.");
        castlingRights = Set.copyOf(castlingRights);
        if (castlingRights.stream().anyMatch(right -> !right.hasStartingPieces(board)))
            throw new IllegalArgumentException("Castling rights must match the position.");
    }

    public static State initial() {
        return new State(Board.initial(), Color.White, null, Set.of(Castling.values()));
    }

    public Piece pieceAt(Square square) {
        return board.pieceAt(square);
    }

    public boolean isInCheck() {
        return board.isInCheck(turn);
    }

    public boolean isCheckmate() {
        return isInCheck() && !board.hasLegalMove(turn, enPassantTarget);
    }

    public boolean isStalemate() {
        return board.hasKing(turn) && board.hasKing(opponent())
                && !isInCheck() && !board.hasLegalMove(turn, enPassantTarget);
    }

    public Optional<Color> winner() {
        return isCheckmate() ? Optional.of(opponent()) : Optional.empty();
    }

    public State move(Move move) {
        if (move == null)
            throw new IllegalArgumentException("Move cannot be null");
        Piece piece = board.pieceAt(move.from());
        if (piece == null)
            throw new IllegalArgumentException("No piece at origin");
        if (piece.color() != turn)
            throw new IllegalArgumentException("Piece does not belong to the current turn");
        Castling castling = piece.isKing() ? Castling.forMove(move, turn) : null;
        Board nextBoard = castling == null ? board.apply(move, enPassantTarget) : castle(move, castling);
        if (nextBoard.isInCheck(turn))
            throw new IllegalArgumentException("Your king would be in check.");
        return new State(nextBoard, opponent(), nextEnPassantTarget(piece, move), nextCastlingRights(move));
    }

    private Board castle(Move move, Castling castling) {
        if (move.promotion() != null)
            throw new IllegalArgumentException("Only a pawn reaching the last rank can promote.");
        if (!castlingRights.contains(castling))
            throw new IllegalArgumentException("The king or rook cannot castle on this side.");
        return board.castle(castling);
    }

    private Set<Castling> nextCastlingRights(Move move) {
        Set<Castling> remaining = new HashSet<>(castlingRights);
        remaining.removeIf(right -> right.isLostAfter(move));
        return remaining;
    }

    private static Square nextEnPassantTarget(Piece piece, Move move) {
        int rankDistance = Math.abs(move.to().rank().symbol() - move.from().rank().symbol());
        if (!piece.isPawn() || rankDistance != 2) return null;
        return new Square(move.from().file(), piece.color() == Color.White ? Rank.R3 : Rank.R6);
    }

    private static boolean validEnPassantTarget(Board board, Color turn, Square target) {
        boolean whiteTurn = turn == Color.White;
        Rank targetRank = whiteTurn ? Rank.R6 : Rank.R3;
        Rank pawnRank = whiteTurn ? Rank.R5 : Rank.R4;
        Rank startRank = whiteTurn ? Rank.R7 : Rank.R2;
        Piece pawn = whiteTurn ? Piece.BlackPawn : Piece.WhitePawn;
        return target.rank() == targetRank && board.pieceAt(target) == null
                && board.pieceAt(target.file(), pawnRank) == pawn && board.pieceAt(target.file(), startRank) == null;
    }

    private Color opponent() {
        return turn == Color.White ? Color.Black : Color.White;
    }
}
