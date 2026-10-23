package software.ulpgc.chess.domain.model;

public record State(Board board, Color turn) {

    public State {
        if (board == null || turn == null)
            throw new IllegalArgumentException("State must have a board and turn");
    }

    public static State initial() {
        return new State(Board.initial(), Color.White);
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
        Color nextTurn = turn == Color.White ? Color.Black : Color.White;
        return new State(nextBoard, nextTurn);
    }
}
