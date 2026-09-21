package software.ulpgc.chess.domain;

import software.ulpgc.chess.domain.pieces.Piece;

public class Tile {
    private final Position position;
    private Piece piece;

    public Tile(Position position) {
        this.position = position;
    }

    public boolean isOccupied() {
        return piece != null;
    }

    public Piece piece() {
        return piece;
    }

    public void place(Piece piece) {
        this.piece = piece;
    }

    public void clear() {
        this.piece = null;
    }

}
