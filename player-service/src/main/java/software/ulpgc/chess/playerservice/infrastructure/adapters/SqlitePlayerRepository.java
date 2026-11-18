package software.ulpgc.chess.playerservice.infrastructure.adapters;

import software.ulpgc.chess.application.domain.model.User;
import software.ulpgc.chess.playerservice.application.ports.PlayerRepository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;

public final class SqlitePlayerRepository implements PlayerRepository {
    private final SqlitePlayerDatabase database;

    public SqlitePlayerRepository(SqlitePlayerDatabase database) {
        this.database = Objects.requireNonNull(database);
    }

    @Override public Optional<User> findById(String id) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Player identifier cannot be empty.");
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT id, name FROM players WHERE id = ?")) {
            statement.setString(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return Optional.empty();
                return Optional.of(new User(rows.getString("id"), rows.getString("name")));
            }
        } catch (SQLException | IllegalArgumentException exception) {
            throw new IllegalStateException("Could not load the player.", exception);
        }
    }

    @Override public void save(User user) {
        if (user == null) throw new IllegalArgumentException("Player cannot be null.");
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO players (id, name) VALUES (?, ?)
                     ON CONFLICT(id) DO UPDATE SET name = excluded.name
                     """)) {
            statement.setString(1, user.id());
            statement.setString(2, user.name());
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not save the player.", exception);
        }
    }
}
