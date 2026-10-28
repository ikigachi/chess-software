package software.ulpgc.chess.application.domain.model;

public record Room(String code, User white, User black, Game game) {

    public Room {
        if (code == null || code.isBlank())
            throw new IllegalArgumentException("Room must have a code");
        if (white == null || game == null)
            throw new IllegalArgumentException("Room must have a creator and game");
        if (black != null && white.id().equals(black.id()))
            throw new IllegalArgumentException("Players must be different users");
    }

    public static Room create(String code, User creator) {
        return new Room(code, creator, null, Game.initial(code));
    }

    public Room join(User user) {
        if (user == null)
            throw new IllegalArgumentException("User cannot be null");
        if (black != null)
            throw new IllegalArgumentException("Room is full");
        if (white.id().equals(user.id()))
            throw new IllegalArgumentException("User already belongs to this room");
        return new Room(code, white, user, game);
    }

    public Room move(User user, Move move) {
        if (black == null)
            throw new IllegalArgumentException("Room must have two players before moving");
        if (colorOf(user) != game.turn())
            throw new IllegalArgumentException("It is not this user's turn");
        return new Room(code, white, black, game.move(move));
    }

    private Color colorOf(User user) {
        if (user == null)
            throw new IllegalArgumentException("User cannot be null");
        if (white.id().equals(user.id())) return Color.White;
        if (black.id().equals(user.id())) return Color.Black;
        throw new IllegalArgumentException("User does not belong to this room");
    }
}
