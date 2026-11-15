package software.ulpgc.chess.application.domain.model;

public enum Castling {
    WhiteKingside(Color.White, true), WhiteQueenside(Color.White, false),
    BlackKingside(Color.Black, true), BlackQueenside(Color.Black, false);

    private final Color color;
    private final Rank rank;
    private final boolean kingside;

    Castling(Color color, boolean kingside) {
        this.color = color;
        this.rank = color == Color.White ? Rank.R1 : Rank.R8;
        this.kingside = kingside;
    }

    Color color() { return color; }
    Square kingFrom() { return new Square(File.E, rank); }
    Square kingTo() { return new Square(kingside ? File.G : File.C, rank); }
    Square rookFrom() { return new Square(kingside ? File.H : File.A, rank); }
    Square rookTo() { return new Square(kingside ? File.F : File.D, rank); }

    static Castling forMove(Move move, Color color) {
        for (Castling castling : values()) {
            if (castling.color == color && castling.kingFrom().equals(move.from())
                    && castling.kingTo().equals(move.to())) return castling;
        }
        return null;
    }

    boolean hasStartingPieces(Board board) {
        Piece king = color == Color.White ? Piece.WhiteKing : Piece.BlackKing;
        Piece rook = color == Color.White ? Piece.WhiteRook : Piece.BlackRook;
        return board.pieceAt(kingFrom()) == king && board.pieceAt(rookFrom()) == rook;
    }

    boolean isLostAfter(Move move) {
        Square rook = rookFrom();
        return kingFrom().equals(move.from()) || rook.equals(move.from()) || rook.equals(move.to());
    }
}
