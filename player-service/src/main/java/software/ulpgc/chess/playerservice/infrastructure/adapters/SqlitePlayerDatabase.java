package software.ulpgc.chess.playerservice.infrastructure.adapters;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

public final class SqlitePlayerDatabase {
    private final Path path;
    private final String url;

    public SqlitePlayerDatabase(Path path) {
        this.path = path.toAbsolutePath().normalize();
        url = "jdbc:sqlite:" + this.path;
    }

    public void initializeSchema() {
        try {
            Files.createDirectories(path.getParent());
            String schema;
            try (var input = SqlitePlayerDatabase.class.getResourceAsStream("/player-schema.sql")) {
                if (input == null) throw new IOException("Missing database schema resource: player-schema.sql");
                schema = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }
            try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
                statement.executeUpdate(schema);
            }
        } catch (IOException | SQLException exception) {
            throw new IllegalStateException("Could not initialize player storage.", exception);
        }
    }

    public Connection openConnection() throws SQLException {
        return DriverManager.getConnection(url);
    }
}
