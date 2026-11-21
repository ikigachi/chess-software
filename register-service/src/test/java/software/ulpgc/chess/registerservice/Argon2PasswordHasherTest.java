package software.ulpgc.chess.registerservice;

import org.junit.jupiter.api.Test;
import software.ulpgc.chess.registerservice.infrastructure.adapters.Argon2PasswordHasher;

import static org.junit.jupiter.api.Assertions.*;

class Argon2PasswordHasherTest {
    @Test void usesIndependentSaltsAndVerifiesTheExactPasswordWithStoredParameters() {
        var passwords = new Argon2PasswordHasher();
        String password = "  my long chess passphrase ♞  ";

        String first = passwords.hash(password);
        String second = passwords.hash(password);

        assertNotEquals(first, second);
        assertNotEquals(password, first);
        assertTrue(first.startsWith("$argon2id$v=19$m=19456,t=2,p=1$"));
        assertTrue(new Argon2PasswordHasher().matches(password, first));
        assertTrue(passwords.matches(password, second));
        assertFalse(passwords.matches(password.strip(), first));
        assertFalse(passwords.matches("a different long passphrase", first));
    }

    @Test void reportsAnInvalidStoredHashAsAStorageProblem() {
        var passwords = new Argon2PasswordHasher();

        assertThrows(IllegalStateException.class, () -> passwords.matches("my long chess passphrase", "broken-hash"));
    }
}
