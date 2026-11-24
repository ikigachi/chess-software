package software.ulpgc.chess.authenticationservice.application.domain.model;

public record AccountCredentials(String accountId, String playerId, String passwordHash) {
    public AccountCredentials {
        if (accountId == null || accountId.isBlank() || playerId == null || playerId.isBlank()
                || passwordHash == null || passwordHash.isBlank())
            throw new IllegalArgumentException("Stored account credentials are incomplete.");
    }
}
