package software.ulpgc.chess.registerservice.application.usecases;

import software.ulpgc.chess.registerservice.application.domain.model.Account;
import software.ulpgc.chess.registerservice.application.domain.model.AccountConflictException;
import software.ulpgc.chess.registerservice.application.ports.AccountRepository;
import software.ulpgc.chess.registerservice.application.ports.PasswordHasher;
import software.ulpgc.chess.registerservice.application.ports.PlayerIdentity;
import software.ulpgc.chess.registerservice.application.ports.AccountIdentity;

import java.util.Objects;
import java.util.UUID;

public final class AccountUseCases {
    private final AccountRepository accounts;
    private final PlayerIdentity identities;
    private final PasswordHasher passwords;
    private final AccountIdentity authentication;

    public AccountUseCases(AccountRepository accounts, PlayerIdentity identities, PasswordHasher passwords) {
        this(accounts, identities, passwords, token -> { throw new SecurityException("Account sessions are not configured."); });
    }

    public AccountUseCases(AccountRepository accounts, PlayerIdentity identities, PasswordHasher passwords, AccountIdentity authentication) {
        this.accounts = Objects.requireNonNull(accounts);
        this.identities = Objects.requireNonNull(identities);
        this.passwords = Objects.requireNonNull(passwords);
        this.authentication = Objects.requireNonNull(authentication);
    }

    public boolean hasAccount(String playerId) {
        return accounts.findByPlayerId(playerId).isPresent();
    }

    public Account deactivateAuthenticatedAccount(String token, String password) {
        validatePassword(password);
        return deactivatePlayerAccount(authentication.resolvePlayerId(token), password);
    }

    public Account registerAccount(String credential, String username, String password) {
        String normalizedUsername = Account.normalizeUsername(username);
        validatePassword(password);
        String playerId = identities.resolvePlayerId(credential);
        if (accounts.findByUsername(normalizedUsername).isPresent() || accounts.findByPlayerId(playerId).isPresent())
            throw new AccountConflictException();
        Account account = Account.create(UUID.randomUUID().toString(), normalizedUsername, playerId);
        accounts.create(account, passwords.hash(password));
        return account;
    }

    public Account deactivateAccount(String credential, String password) {
        validatePassword(password);
        String playerId = identities.resolvePlayerId(credential);
        return deactivatePlayerAccount(playerId, password);
    }

    private Account deactivatePlayerAccount(String playerId, String password) {
        Account account = accounts.findByPlayerId(playerId).filter(Account::isActive)
                .orElseThrow(() -> new SecurityException("Account credentials are invalid."));
        String passwordHash = accounts.findPasswordHash(account.id())
                .orElseThrow(() -> new IllegalStateException("Account has no stored credentials."));
        if (!passwords.matches(password, passwordHash))
            throw new SecurityException("Account credentials are invalid.");
        accounts.deactivate(account.id());
        return account.deactivate();
    }

    private void validatePassword(String password) {
        int length = password == null ? 0 : password.codePointCount(0, password.length());
        if (length < 15 || length > 128)
            throw new IllegalArgumentException("Enter a password between 15 and 128 characters.");
    }
}
