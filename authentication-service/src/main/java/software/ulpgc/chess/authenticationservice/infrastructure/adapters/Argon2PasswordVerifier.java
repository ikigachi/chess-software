package software.ulpgc.chess.authenticationservice.infrastructure.adapters;

import com.password4j.Argon2Function;
import com.password4j.Password;
import com.password4j.types.Argon2;
import software.ulpgc.chess.authenticationservice.application.ports.PasswordVerifier;

import java.util.Optional;
import java.util.UUID;

public final class Argon2PasswordVerifier implements PasswordVerifier {
    private final String dummyHash = Password.hash(UUID.randomUUID().toString()).addRandomSalt(16)
            .with(Argon2Function.getInstance(19456, 2, 1, 32, Argon2.ID)).getResult();

    @Override public boolean matches(String password, Optional<String> passwordHash) {
        String encoded = passwordHash.orElse(dummyHash);
        try {
            boolean matches = Password.check(password, encoded).with(Argon2Function.getInstanceFromHash(encoded));
            return passwordHash.isPresent() && matches;
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Stored password hash is invalid.", exception);
        }
    }
}
