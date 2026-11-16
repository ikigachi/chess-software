package software.ulpgc.chess.application.domain.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class Game {
    private final String id;
    private final State state;
    private final List<Move> moves;

    private Game(String id, State state, List<Move> moves) {
        if (id == null || id.isBlank())
            throw new IllegalArgumentException("Game must have an identifier");
        if (state == null || moves == null)
            throw new IllegalArgumentException("Game must have a state and move history");
        this.id = id;
        this.state = state;
        this.moves = List.copyOf(moves);
    }

    public static Game initial(String id) {
        return new Game(id, State.initial(), List.of());
    }

    public static Game replay(String id, List<Move> moves) {
        if (moves == null) throw new IllegalArgumentException("Game must have a move history");
        List<Move> history = List.copyOf(moves);
        State state = State.initial();
        for (Move move : history) state = state.move(move);
        return new Game(id, state, history);
    }

    public static Game restore(String id, State state, List<Move> moves) {
        if (state == null) throw new IllegalArgumentException("Game must have a state");
        Game restored = replay(id, moves);
        State replayed = restored.state;
        if (!replayed.board().equals(state.board()) || replayed.turn() != state.turn()
                || state.enPassantTarget() != null && !state.enPassantTarget().equals(replayed.enPassantTarget())
                || !state.castlingRights().isEmpty() && !state.castlingRights().equals(replayed.castlingRights()))
            throw new IllegalArgumentException("Game state does not match its move history");
        return restored;
    }

    public String id() { return id; }
    public State state() { return state; }
    public List<Move> moves() { return moves; }
    public Piece pieceAt(Square square) { return state.pieceAt(square); }

    public Color turn() {
        return state.turn();
    }

    public Game move(Move move) {
        State nextState = state.move(move);
        List<Move> nextMoves = new ArrayList<>(moves);
        nextMoves.add(move);
        return new Game(id, nextState, nextMoves);
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) return true;
        if (!(object instanceof Game other)) return false;
        return id.equals(other.id) && state.equals(other.state) && moves.equals(other.moves);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, state, moves);
    }

    @Override
    public String toString() {
        return "Game[id=" + id + ", state=" + state + ", moves=" + moves + "]";
    }
}
