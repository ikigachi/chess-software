package software.ulpgc.chess.application.domain.model;

public record Move(Square from, Square to, Promotion promotion) {

    public Move(Square from, Square to) {
        this(from, to, null);
    }

    public Move {
        if (from == null || to == null)
            throw new IllegalArgumentException("Move must have an origin and destination");
    }
}
