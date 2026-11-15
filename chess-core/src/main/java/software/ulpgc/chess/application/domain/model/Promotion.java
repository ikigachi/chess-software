package software.ulpgc.chess.application.domain.model;

import static software.ulpgc.chess.application.domain.model.Piece.*;

public enum Promotion {
    Queen, Rook, Bishop, Knight;

    Piece pieceOf(Color color) {
        return switch (this) {
            case Queen -> color == Color.White ? WhiteQueen : BlackQueen;
            case Rook -> color == Color.White ? WhiteRook : BlackRook;
            case Bishop -> color == Color.White ? WhiteBishop : BlackBishop;
            case Knight -> color == Color.White ? WhiteKnight : BlackKnight;
        };
    }
}
