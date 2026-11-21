package software.ulpgc.chess.registerservice.infrastructure.adapters;

import com.password4j.Argon2Function;
import com.password4j.Password;
import com.password4j.types.Argon2;
import software.ulpgc.chess.registerservice.application.ports.PasswordHasher;

public final class Argon2PasswordHasher implements PasswordHasher {
    private final Argon2Function function = Argon2Function.getInstance(19456, 2, 1, 32, Argon2.ID);

    @Override public String hash(String password) {
        return Password.hash(password).addRandomSalt(16).with(function).getResult();
    }

    @Override public boolean matches(String password, String passwordHash) {
        try {
            return Password.check(password, passwordHash).with(Argon2Function.getInstanceFromHash(passwordHash));
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Stored password hash is invalid.", exception);
        }
    }
}
