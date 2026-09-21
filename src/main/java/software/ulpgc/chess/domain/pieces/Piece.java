package software.ulpgc.chess.domain.pieces;

import software.ulpgc.chess.domain.Color;
import software.ulpgc.chess.domain.Position;

public abstract class Piece {
    private final Color color;

    protected Piece(Color color) {
        this.color = color;
    }

    public Color color() {
        return color;
    }

    public abstract boolean canMove(Position origin, Position destination);
}
