package software.ulpgc.chess.authenticationservice.infrastructure.adapters;

import software.ulpgc.chess.authenticationservice.application.domain.model.AccountCredentials;
import software.ulpgc.chess.authenticationservice.application.ports.AccountCredentialsReader;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;

public final class SqliteAccountCredentialsReader implements AccountCredentialsReader {
    private final SqliteAuthenticationDatabase database;

    public SqliteAccountCredentialsReader(SqliteAuthenticationDatabase database) {
        this.database = Objects.requireNonNull(database);
    }

    @Override public Optional<AccountCredentials> findActiveByUsername(String username) {
        try (Connection connection = database.openConnection();
             PreparedStatement query = connection.prepareStatement("""
                     SELECT accounts.id, accounts.player_id, account_passwords.password_hash
                     FROM accounts JOIN account_passwords ON accounts.id = account_passwords.account_id
                     WHERE accounts.username = ? AND accounts.status = 'Active'
                     """)) {
            query.setString(1, username);
            try (var rows = query.executeQuery()) {
                if (!rows.next()) return Optional.empty();
                return Optional.of(new AccountCredentials(rows.getString("id"), rows.getString("player_id"),
                        rows.getString("password_hash")));
            }
        } catch (SQLException | IllegalArgumentException exception) {
            throw new IllegalStateException("Could not read account credentials.", exception);
        }
    }
}
