package software.ulpgc.chess.registerservice;

import org.junit.jupiter.api.Test;
import software.ulpgc.chess.registerservice.application.domain.model.Account;
import software.ulpgc.chess.registerservice.application.domain.model.AccountStatus;

import static org.junit.jupiter.api.Assertions.*;

class AccountTest {
    @Test void normalizesTheLoginNameWithoutChangingAccountOrPlayerIdentity() {
        Account account = Account.create("account-one", " Alice_01.Test-User ", "existing-guest-id");

        assertEquals("account-one", account.id());
        assertEquals("alice_01.test-user", account.username());
        assertEquals("existing-guest-id", account.playerId());
        assertTrue(account.isActive());
        assertEquals(AccountStatus.Active, account.status());
        assertEquals("a".repeat(40), Account.create("account-two", "A".repeat(40), "player-two").username());
    }

    @Test void deactivationPreservesTheAssociationAndDoesNotChangeTheOriginalAccount() {
        Account active = Account.create("account-one", "alice", "existing-guest-id");

        Account inactive = active.deactivate();

        assertTrue(active.isActive());
        assertFalse(inactive.isActive());
        assertEquals(active.id(), inactive.id());
        assertEquals(active.username(), inactive.username());
        assertEquals(active.playerId(), inactive.playerId());
        assertEquals(inactive, inactive.deactivate());
    }

    @Test void rejectsMissingIdentityStatusAndUnsupportedLoginNames() {
        for (String missing : new String[]{null, "", " \t"}) {
            assertThrows(IllegalArgumentException.class, () -> Account.create(missing, "alice", "player-one"));
            assertThrows(IllegalArgumentException.class, () -> Account.create("account-one", "alice", missing));
        }
        for (String username : new String[]{null, "", "  ", "ab", "a".repeat(41), "alice bob", "alice@example.com", "álîce", "Kelvin", "' OR 1=1"})
            assertThrows(IllegalArgumentException.class, () -> Account.create("account-one", username, "player-one"));
        assertThrows(IllegalArgumentException.class, () -> new Account("account-one", "alice", "player-one", null));
    }
}
