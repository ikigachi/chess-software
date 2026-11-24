package software.ulpgc.chess.authenticationservice.infrastructure.adapters;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

public final class SqliteAuthenticationDatabase {
    private final String url;

    public SqliteAuthenticationDatabase(Path path) {
        url = "jdbc:sqlite:" + path.toAbsolutePath().normalize().toUri().toASCIIString() + "?mode=rw";
    }

    public void initializeSchema() {
        try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("""
                    SELECT accounts.id, accounts.player_id, accounts.status, account_passwords.password_hash
                    FROM accounts JOIN account_passwords ON accounts.id = account_passwords.account_id WHERE 1 = 0
                    """)) {
                rows.getMetaData();
            }
            String schema;
            try (var input = SqliteAuthenticationDatabase.class.getResourceAsStream("/authentication-schema.sql")) {
                if (input == null) throw new IOException("Missing authentication schema resource.");
                schema = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }
            statement.executeUpdate(schema);
        } catch (IOException | SQLException exception) {
            throw new IllegalStateException("Could not initialize authentication storage. Initialize account storage first.", exception);
        }
    }

    public Connection openConnection() throws SQLException {
        Connection connection = DriverManager.getConnection(url);
        try {
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA foreign_keys = ON");
            }
            return connection;
        } catch (SQLException exception) {
            try {
                connection.close();
            } catch (SQLException closeFailure) {
                exception.addSuppressed(closeFailure);
            }
            throw exception;
        }
    }
}
