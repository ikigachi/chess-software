package software.ulpgc.chess.gameservice.infrastructure.adapters;

public final class AccountConflictException extends IllegalArgumentException {
    public AccountConflictException() {
        super("An account already exists for this username or player.");
    }
}
