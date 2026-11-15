package software.ulpgc.chess.application.domain.model;

import java.util.Optional;

public record State(Board board, Color turn) {

    public State {
        if (board == null || turn == null)
            throw new IllegalArgumentException("State must have a board and turn");
    }

    public static State initial() {
        return new State(Board.initial(), Color.White);
    }

    public Piece pieceAt(Square square) {
        return board.pieceAt(square);
    }

    public boolean isCheckmate() {
        return board.isInCheck(turn) && !board.hasLegalMove(turn);
    }

    public boolean isStalemate() {
        return board.hasKing(turn) && board.hasKing(opponent())
                && !board.isInCheck(turn) && !board.hasLegalMove(turn);
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
        Board nextBoard = board.move(move.from(), move.to());
        if (nextBoard.isInCheck(turn))
            throw new IllegalArgumentException("Your king would be in check.");
        return new State(nextBoard, opponent());
    }

    private Color opponent() {
        return turn == Color.White ? Color.Black : Color.White;
    }
}
