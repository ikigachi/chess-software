package software.ulpgc.chess.authenticationservice.application.ports;

import java.util.Optional;

public interface PasswordVerifier {
    boolean matches(String password, Optional<String> passwordHash);
}
