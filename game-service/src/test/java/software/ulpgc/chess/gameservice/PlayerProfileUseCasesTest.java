package software.ulpgc.chess.gameservice;

import org.junit.jupiter.api.Test;
import software.ulpgc.chess.application.domain.model.User;
import software.ulpgc.chess.gameservice.application.ports.PlayerProfileUpdater;
import software.ulpgc.chess.gameservice.application.usecases.PlayerProfileUseCases;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class PlayerProfileUseCasesTest {
    @Test void normalizesTheNameBeforeCallingTheServiceAndLoadsTheUpdatedProfile() {
        User current = new User("player", "Alice");
        AtomicReference<User> stored = new AtomicReference<>(current);
        AtomicReference<String> receivedName = new AtomicReference<>();
        PlayerProfileUpdater updater = (credential, name) -> {
            assertEquals("session-credential", credential);
            receivedName.set(name);
            User updated = new User(current.id(), name);
            stored.set(updated);
            return updated;
        };
        var profiles = new PlayerProfileUseCases(credential -> stored.get(), updater);

        User renamed = profiles.updatePlayerDisplayName(current, "session-credential", " Renamed ");

        assertEquals("Renamed", receivedName.get());
        assertEquals(new User(current.id(), "Renamed"), renamed);
        assertEquals(renamed, profiles.loadPlayerProfile(current, "session-credential"));
    }

    @Test void invalidNamesDoNotCallTheRemoteUpdater() {
        User current = new User("player", "Alice");
        AtomicInteger writes = new AtomicInteger();
        var profiles = new PlayerProfileUseCases(credential -> current, (credential, name) -> {
            writes.incrementAndGet();
            return new User(current.id(), name);
        });

        for (String invalid : new String[]{null, "", " ", "x".repeat(41)}) {
            assertThrows(IllegalArgumentException.class, () -> profiles.updatePlayerDisplayName(current, "credential", invalid));
        }
        assertEquals(0, writes.get());
        assertEquals(current, profiles.loadPlayerProfile(current, "credential"));
    }

    @Test void rejectsProfilesThatWouldChangeTheSessionIdentity() {
        User current = new User("player", "Alice");
        var profiles = new PlayerProfileUseCases(credential -> new User("other", "Other"),
                (credential, name) -> new User("other", name));

        assertThrows(IllegalStateException.class, () -> profiles.loadPlayerProfile(current, "credential"));
        assertThrows(IllegalStateException.class, () -> profiles.updatePlayerDisplayName(current, "credential", "Renamed"));
    }
}
