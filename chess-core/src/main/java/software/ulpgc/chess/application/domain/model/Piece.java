package software.ulpgc.chess.application.domain.model;


import static software.ulpgc.chess.application.domain.model.Color.*;

public enum Piece {
    WhitePawn,
    WhiteRook,
    WhiteKnight,
    WhiteBishop,
    WhiteKing,
    WhiteQueen,
    BlackPawn,
    BlackRook,
    BlackKnight,
    BlackBishop,
    BlackKing,
    BlackQueen;

    public Color color() {
        return isWhite() ? White : Black;
    }

    boolean canMove(Square from, Square to, boolean capture) {
        int fileDistance = Math.abs(to.file().symbol() - from.file().symbol());
        int rankDifference = to.rank().symbol() - from.rank().symbol();
        int rankDistance = Math.abs(rankDifference);
        if (fileDistance == 0 && rankDistance == 0) return false;
        return switch (this) {
            case WhitePawn, BlackPawn -> pawnCanMove(from, fileDistance, rankDifference, capture);
            case WhiteRook, BlackRook -> fileDistance == 0 || rankDistance == 0;
            case WhiteBishop, BlackBishop -> fileDistance == rankDistance;
            case WhiteQueen, BlackQueen -> fileDistance == 0 || rankDistance == 0 || fileDistance == rankDistance;
            case WhiteKnight, BlackKnight -> fileDistance == 1 && rankDistance == 2 || fileDistance == 2 && rankDistance == 1;
            case WhiteKing, BlackKing -> Math.max(fileDistance, rankDistance) == 1;
        };
    }

    private boolean pawnCanMove(Square from, int fileDistance, int rankDifference, boolean capture) {
        int direction = color() == White ? 1 : -1;
        if (capture) return fileDistance == 1 && rankDifference == direction;
        int initialRank = color() == White ? 2 : 7;
        return fileDistance == 0 && (rankDifference == direction
                || from.rank().symbol() == initialRank && rankDifference == 2 * direction);
    }

    boolean isPawn() {
        return this == WhitePawn || this == BlackPawn;
    }

    boolean isRook() {
        return this == WhiteRook || this == BlackRook;
    }

    boolean isKnight() {
        return this == WhiteKnight || this == BlackKnight;
    }

    boolean isBishop() {
        return this == WhiteBishop || this == BlackBishop;
    }

    boolean isKing() {
        return this == WhiteKing || this == BlackKing;
    }

    boolean isQueen() {
        return this == WhiteQueen || this == BlackQueen;
    }

    private boolean isWhite() {
        return this == WhitePawn ||
                this == WhiteRook ||
                this == WhiteKnight ||
                this == WhiteBishop ||
                this == WhiteKing ||
                this == WhiteQueen;
    }

}
