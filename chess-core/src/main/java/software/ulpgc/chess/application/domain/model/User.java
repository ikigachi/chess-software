package software.ulpgc.chess.application.domain.model;

public record User(String id, String name) {

    public User {
        if (id == null || id.isBlank())
            throw new IllegalArgumentException("User must have an identifier");
        if (name == null || name.isBlank())
            throw new IllegalArgumentException("User must have a name");
    }
}
