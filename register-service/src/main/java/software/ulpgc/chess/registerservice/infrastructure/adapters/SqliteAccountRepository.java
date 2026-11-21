package software.ulpgc.chess.registerservice.infrastructure.adapters;

import software.ulpgc.chess.registerservice.application.domain.model.Account;
import software.ulpgc.chess.registerservice.application.domain.model.AccountConflictException;
import software.ulpgc.chess.registerservice.application.domain.model.AccountStatus;
import software.ulpgc.chess.registerservice.application.ports.AccountRepository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;

public final class SqliteAccountRepository implements AccountRepository {
    private final SqliteAccountDatabase database;

    public SqliteAccountRepository(SqliteAccountDatabase database) {
        this.database = Objects.requireNonNull(database);
    }

    @Override public void create(Account account, String passwordHash) {
        if (account == null) throw new IllegalArgumentException("Account cannot be null.");
        if (passwordHash == null || passwordHash.isBlank())
            throw new IllegalArgumentException("Password hash cannot be empty.");
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                insertAccount(connection, account);
                insertPasswordHash(connection, account.id(), passwordHash);
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    exception.addSuppressed(rollbackFailure);
                }
                throw exception;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not create the account.", exception);
        }
    }

    private void insertAccount(Connection connection, Account account) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                     INSERT INTO accounts (id, username, player_id, status) VALUES (?, ?, ?, ?)
                     ON CONFLICT DO NOTHING
                     """)) {
            insert.setString(1, account.id());
            insert.setString(2, account.username());
            insert.setString(3, account.playerId());
            insert.setString(4, account.status().name());
            if (insert.executeUpdate() == 0)
                throw new AccountConflictException();
        }
    }

    private void insertPasswordHash(Connection connection, String accountId, String passwordHash) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO account_passwords (account_id, password_hash) VALUES (?, ?)")) {
            insert.setString(1, accountId);
            insert.setString(2, passwordHash);
            insert.executeUpdate();
        }
    }

    @Override public Optional<Account> findByUsername(String username) {
        return findAccount("SELECT id, username, player_id, status FROM accounts WHERE username = ?",
                Account.normalizeUsername(username));
    }

    @Override public Optional<Account> findByPlayerId(String playerId) {
        if (playerId == null || playerId.isBlank())
            throw new IllegalArgumentException("Player identifier cannot be empty.");
        return findAccount("SELECT id, username, player_id, status FROM accounts WHERE player_id = ?", playerId);
    }

    @Override public Optional<String> findPasswordHash(String accountId) {
        validateAccountId(accountId);
        try (Connection connection = database.openConnection();
             PreparedStatement query = connection.prepareStatement(
                     "SELECT password_hash FROM account_passwords WHERE account_id = ?")) {
            query.setString(1, accountId);
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) return Optional.empty();
                String passwordHash = rows.getString("password_hash");
                if (passwordHash == null || passwordHash.isBlank())
                    throw new SQLException("Stored password hash cannot be empty.");
                return Optional.of(passwordHash);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not load account credentials.", exception);
        }
    }

    @Override public void deactivate(String accountId) {
        validateAccountId(accountId);
        try (Connection connection = database.openConnection();
             PreparedStatement update = connection.prepareStatement(
                     "UPDATE accounts SET status = 'Inactive' WHERE id = ? AND status = 'Active'")) {
            update.setString(1, accountId);
            if (update.executeUpdate() == 0) throw new SecurityException("Account credentials are invalid.");
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not deactivate the account.", exception);
        }
    }

    private void validateAccountId(String accountId) {
        if (accountId == null || accountId.isBlank())
            throw new IllegalArgumentException("Account identifier cannot be empty.");
    }

    private Optional<Account> findAccount(String sql, String value) {
        try (Connection connection = database.openConnection();
             PreparedStatement query = connection.prepareStatement(sql)) {
            query.setString(1, value);
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) return Optional.empty();
                return Optional.of(readAccount(rows));
            }
        } catch (SQLException | IllegalArgumentException exception) {
            throw new IllegalStateException("Could not load the account.", exception);
        }
    }

    private Account readAccount(ResultSet rows) throws SQLException {
        return new Account(rows.getString("id"), rows.getString("username"), rows.getString("player_id"),
                AccountStatus.valueOf(rows.getString("status")));
    }
}
