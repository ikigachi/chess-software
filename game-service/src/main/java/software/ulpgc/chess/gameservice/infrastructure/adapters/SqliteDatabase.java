package software.ulpgc.chess.gameservice.infrastructure.adapters;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

public final class SqliteDatabase {
    private final Path path;
    private final String url;

    public SqliteDatabase(Path path) {
        this.path = path.toAbsolutePath().normalize();
        url = "jdbc:sqlite:" + this.path;
    }

    public void initialize() {
        try {
            Files.createDirectories(path.getParent());
            String schema;
            try (var input = SqliteDatabase.class.getResourceAsStream("/schema.sql")) {
                if (input == null) throw new IOException("Missing database schema resource: schema.sql");
                schema = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }
            try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
                statement.executeUpdate(schema);
            }
        } catch (IOException | SQLException exception) {
            throw new IllegalStateException("Could not initialize room storage.", exception);
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
