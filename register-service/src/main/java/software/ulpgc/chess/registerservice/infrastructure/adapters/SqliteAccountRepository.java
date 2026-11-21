package software.ulpgc.chess.registerservice.infrastructure.adapters;

import software.ulpgc.chess.registerservice.application.domain.model.Account;
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

    @Override public void create(Account account) {
        if (account == null) throw new IllegalArgumentException("Account cannot be null.");
        try (Connection connection = database.openConnection();
             PreparedStatement insert = connection.prepareStatement("""
                     INSERT INTO accounts (id, username, player_id, status) VALUES (?, ?, ?, ?)
                     ON CONFLICT DO NOTHING
                     """)) {
            insert.setString(1, account.id());
            insert.setString(2, account.username());
            insert.setString(3, account.playerId());
            insert.setString(4, account.status().name());
            if (insert.executeUpdate() == 0)
                throw new IllegalArgumentException("An account already exists with this identifier, username or player.");
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not create the account.", exception);
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
