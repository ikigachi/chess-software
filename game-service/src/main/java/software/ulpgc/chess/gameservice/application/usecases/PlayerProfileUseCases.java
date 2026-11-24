package software.ulpgc.chess.gameservice.application.usecases;

import software.ulpgc.chess.application.domain.model.User;
import software.ulpgc.chess.gameservice.application.ports.PlayerIdentity;
import software.ulpgc.chess.gameservice.application.ports.PlayerProfileUpdater;
import software.ulpgc.chess.gameservice.application.ports.AccountPlayerProfiles;

import java.util.Objects;

public final class PlayerProfileUseCases {
    private final PlayerIdentity identities;
    private final PlayerProfileUpdater profiles;
    private final AccountPlayerProfiles accounts;

    public PlayerProfileUseCases(PlayerIdentity identities, PlayerProfileUpdater profiles) {
        this(identities, profiles, null);
    }

    public PlayerProfileUseCases(PlayerIdentity identities, PlayerProfileUpdater profiles, AccountPlayerProfiles accounts) {
        this.identities = Objects.requireNonNull(identities);
        this.profiles = Objects.requireNonNull(profiles);
        this.accounts = accounts;
    }

    public User loadAccountProfile(User currentPlayer, String token) {
        if (accounts == null) throw new SecurityException("Account profile access is not configured.");
        return requireSamePlayer(currentPlayer, accounts.loadProfile(token));
    }

    public User updateAccountProfile(User currentPlayer, String token, String name) {
        if (accounts == null) throw new SecurityException("Account profile access is not configured.");
        return requireSamePlayer(currentPlayer, accounts.updateProfile(token, User.normalizeName(name)));
    }

    public User loadPlayerProfile(User currentPlayer, String credential) {
        Objects.requireNonNull(currentPlayer);
        return requireSamePlayer(currentPlayer, identities.resolve(credential));
    }

    public User updatePlayerDisplayName(User currentPlayer, String credential, String name) {
        Objects.requireNonNull(currentPlayer);
        String normalizedName = User.normalizeName(name);
        return requireSamePlayer(currentPlayer, profiles.updateDisplayName(credential, normalizedName));
    }

    private User requireSamePlayer(User currentPlayer, User profile) {
        if (profile == null || !currentPlayer.id().equals(profile.id()))
            throw new IllegalStateException("Player service returned a different player identity.");
        return profile;
    }
}
