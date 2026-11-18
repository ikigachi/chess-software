package software.ulpgc.chess.application.domain.model;

public record User(String id, String name) {

    public User {
        if (id == null || id.isBlank())
            throw new IllegalArgumentException("User must have an identifier");
        name = normalizeName(name);
    }

    public static String normalizeName(String name) {
        if (name == null || name.isBlank() || name.strip().length() > 40)
            throw new IllegalArgumentException("Enter a name between 1 and 40 characters.");
        return name.strip();
    }
}
