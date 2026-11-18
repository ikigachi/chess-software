package software.ulpgc.chess.playerservice;

import org.junit.jupiter.api.Test;
import software.ulpgc.chess.application.domain.model.User;
import software.ulpgc.chess.playerservice.application.usecases.ResolvePlayerUseCase;
import software.ulpgc.chess.playerservice.infrastructure.adapters.InMemoryPlayerRepository;

import static org.junit.jupiter.api.Assertions.*;

class ResolvePlayerUseCaseTest {
    @Test void preservesExistingBrowserIdentitiesAcrossServiceInstances() {
        String credential = "123e4567-e89b-12d3-a456-426614174000";
        User expected = new User("986c0dc956dc822b5d8f698661b9eb1ef880786ff9043c16744d2a420e99e9bb", "Guest");

        assertEquals(expected, new ResolvePlayerUseCase(new InMemoryPlayerRepository()).execute(credential));
        assertEquals(expected, new ResolvePlayerUseCase(new InMemoryPlayerRepository()).execute(credential));
    }

    @Test void independentBrowsersHaveDifferentIdentities() {
        var resolve = new ResolvePlayerUseCase(new InMemoryPlayerRepository());

        assertNotEquals(resolve.execute("123e4567-e89b-12d3-a456-426614174000").id(),
                resolve.execute("123e4567-e89b-12d3-a456-426614174001").id());
    }

    @Test void rejectsMissingOrNonCanonicalCredentials() {
        var resolve = new ResolvePlayerUseCase(new InMemoryPlayerRepository());
        for (String invalid : new String[]{null, "", "player", "123e4567-e89b-12d3-a456-42661417400z",
                "123E4567-E89B-12D3-A456-426614174000"}) {
            assertThrows(IllegalArgumentException.class, () -> resolve.execute(invalid));
        }
    }
}
