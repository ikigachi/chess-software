package software.ulpgc.chess.registerservice.infrastructure.adapters;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

public final class SqliteAccountDatabase {
    private final Path path;
    private final String url;

    public SqliteAccountDatabase(Path path) {
        this.path = path.toAbsolutePath().normalize();
        this.url = "jdbc:sqlite:" + this.path;
    }

    public void initializeSchema() {
        try {
            Files.createDirectories(path.getParent());
            String schema;
            try (var input = SqliteAccountDatabase.class.getResourceAsStream("/account-schema.sql")) {
                if (input == null) throw new IOException("Missing database schema resource: account-schema.sql");
                schema = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }
            try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
                statement.executeUpdate(schema);
            }
        } catch (IOException | SQLException exception) {
            throw new IllegalStateException("Could not initialize account storage.", exception);
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
