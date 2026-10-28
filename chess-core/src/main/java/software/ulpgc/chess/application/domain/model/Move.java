package software.ulpgc.chess.application.domain.model;

public record Move(Square from, Square to) {

    public Move {
        if (from == null || to == null)
            throw new IllegalArgumentException("Move must have an origin and destination");
    }
}
