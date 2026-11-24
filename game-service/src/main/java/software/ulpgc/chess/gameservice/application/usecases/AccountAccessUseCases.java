package software.ulpgc.chess.gameservice.application.usecases;

import software.ulpgc.chess.application.domain.model.User;
import software.ulpgc.chess.gameservice.application.ports.AccountAuthentication;
import software.ulpgc.chess.gameservice.application.ports.AccountRegistration;

import java.util.Objects;

public final class AccountAccessUseCases {
    private final AccountAuthentication authentication;
    private final AccountRegistration registration;

    public AccountAccessUseCases(AccountAuthentication authentication, AccountRegistration registration) {
        this.authentication = Objects.requireNonNull(authentication);
        this.registration = Objects.requireNonNull(registration);
    }

    public void requireGuestAccess(User player) {
        if (registration.hasAccount(player.id())) throw new SecurityException("Sign in to use this registered player.");
    }

    public AccountAuthentication.Identity resumeAccount(String token) {
        return authentication.resume(token);
    }

    public AccountAuthentication.Grant login(String username, String password) {
        return authentication.login(username, password);
    }

    public void logout(String token) {
        authentication.logout(token);
    }

    public void register(User player, String credential, String username, String password) {
        requireSamePlayer(player, registration.register(credential, username, password));
    }

    public void deactivate(User player, String token, String password) {
        requireSamePlayer(player, registration.deactivate(token, password));
    }

    private void requireSamePlayer(User player, String playerId) {
        if (!player.id().equals(playerId)) throw new IllegalStateException("Account service returned a different player identity.");
    }
}
