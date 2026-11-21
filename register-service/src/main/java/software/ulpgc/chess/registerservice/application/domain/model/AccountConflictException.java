package software.ulpgc.chess.registerservice.application.domain.model;

public final class AccountConflictException extends IllegalArgumentException {
    public AccountConflictException() {
        super("An account already exists with this identifier, username or player.");
    }
}
