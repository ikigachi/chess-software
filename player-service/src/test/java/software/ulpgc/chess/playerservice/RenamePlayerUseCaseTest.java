package software.ulpgc.chess.playerservice;

import org.junit.jupiter.api.Test;
import software.ulpgc.chess.application.domain.model.User;
import software.ulpgc.chess.playerservice.application.ports.PlayerRepository;
import software.ulpgc.chess.playerservice.application.usecases.RenamePlayerUseCase;
import software.ulpgc.chess.playerservice.application.usecases.ResolvePlayerUseCase;
import software.ulpgc.chess.playerservice.infrastructure.adapters.InMemoryPlayerRepository;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class RenamePlayerUseCaseTest {
    private final String credential = "123e4567-e89b-12d3-a456-426614174000";

    @Test void updatesTheNameWithoutChangingIdentityOrAnotherPlayer() {
        var repository = new InMemoryPlayerRepository();
        var resolve = new ResolvePlayerUseCase(repository);
        var rename = new RenamePlayerUseCase(resolve, repository);
        User original = resolve.execute(credential);
        String other = "123e4567-e89b-12d3-a456-426614174001";

        User renamed = rename.execute(credential, " Zoë ");

        assertEquals(original.id(), renamed.id());
        assertEquals("Zoë", renamed.name());
        assertEquals(renamed, resolve.execute(credential));
        assertEquals("Guest", resolve.execute(other).name());
        assertTrue(repository.findById(resolve.execute(other).id()).isEmpty());
    }

    @Test void invalidNamesAndCredentialsPreserveTheStoredProfile() {
        var repository = new InMemoryPlayerRepository();
        var resolve = new ResolvePlayerUseCase(repository);
        var rename = new RenamePlayerUseCase(resolve, repository);
        User saved = rename.execute(credential, "Alice");

        for (String invalid : new String[]{null, "", " ", "x".repeat(41)}) {
            assertThrows(IllegalArgumentException.class, () -> rename.execute(credential, invalid));
            assertEquals(saved, resolve.execute(credential));
        }
        assertThrows(IllegalArgumentException.class, () -> rename.execute("wrong", "Renamed"));
        assertEquals(saved, resolve.execute(credential));
    }

    @Test void reportsAStorageFailureWithoutChangingTheExistingProfile() {
        User saved = new User("986c0dc956dc822b5d8f698661b9eb1ef880786ff9043c16744d2a420e99e9bb", "Alice");
        var failure = new IllegalStateException("Player storage is unavailable.");
        PlayerRepository repository = new PlayerRepository() {
            @Override public Optional<User> findById(String id) {
                return saved.id().equals(id) ? Optional.of(saved) : Optional.empty();
            }
            @Override public void save(User user) { throw failure; }
        };
        var resolve = new ResolvePlayerUseCase(repository);
        var rename = new RenamePlayerUseCase(resolve, repository);

        assertSame(failure, assertThrows(IllegalStateException.class, () -> rename.execute(credential, "Renamed")));
        assertEquals(saved, resolve.execute(credential));
    }
}
