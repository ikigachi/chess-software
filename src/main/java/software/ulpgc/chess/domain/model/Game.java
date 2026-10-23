package software.ulpgc.chess.domain.model;

import java.util.ArrayList;
import java.util.List;

public record Game(String id, State state, List<Move> moves) {

    public Game {
        if (id == null || id.isBlank())
            throw new IllegalArgumentException("Game must have an identifier");
        if (state == null || moves == null)
            throw new IllegalArgumentException("Game must have a state and move history");
        moves = List.copyOf(moves);
    }

    public static Game initial(String id) {
        return new Game(id, State.initial(), List.of());
    }

    public Game move(Move move) {
        State nextState = state.move(move);
        List<Move> nextMoves = new ArrayList<>(moves);
        nextMoves.add(move);
        return new Game(id, nextState, nextMoves);
    }
}
