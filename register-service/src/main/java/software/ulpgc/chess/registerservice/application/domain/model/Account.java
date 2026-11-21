package software.ulpgc.chess.registerservice.application.domain.model;

import java.util.Locale;

public record Account(String id, String username, String playerId, AccountStatus status) {
    public Account {
        if (id == null || id.isBlank())
            throw new IllegalArgumentException("Account identifier cannot be empty.");
        username = normalizeUsername(username);
        if (playerId == null || playerId.isBlank())
            throw new IllegalArgumentException("Player identifier cannot be empty.");
        if (status == null)
            throw new IllegalArgumentException("Account status is required.");
    }

    public static Account create(String id, String username, String playerId) {
        return new Account(id, username, playerId, AccountStatus.Active);
    }

    public static String normalizeUsername(String username) {
        String stripped = username == null ? null : username.strip();
        if (stripped == null || !stripped.matches("[A-Za-z0-9._-]{3,40}"))
            throw new IllegalArgumentException("Enter a username between 3 and 40 letters, digits, dots, underscores or hyphens.");
        return stripped.toLowerCase(Locale.ROOT);
    }

    public boolean isActive() {
        return status == AccountStatus.Active;
    }

    public Account deactivate() {
        return new Account(id, username, playerId, AccountStatus.Inactive);
    }
}
