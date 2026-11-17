package software.ulpgc.chess.gameservice;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.ulpgc.chess.gameservice.infrastructure.adapters.SqliteDatabase;

import java.nio.file.Path;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;

class SqliteDatabaseTest {
    @Test void everyConnectionRejectsMovesWithoutARoom(@TempDir Path directory) throws Exception {
        SqliteDatabase database = new SqliteDatabase(directory.resolve("rooms.sqlite"));
        database.initialize();
        for (int attempt = 0; attempt < 2; attempt++) {
            try (var connection = database.openConnection();
                 var statement = connection.prepareStatement(
                         "INSERT INTO moves (room_code, ply, origin, destination) VALUES (?, ?, ?, ?)")) {
                statement.setString(1, "MISSING");
                statement.setInt(2, 1);
                statement.setString(3, "e2");
                statement.setString(4, "e4");
                assertThrows(SQLException.class, statement::executeUpdate);
            }
        }
    }
}
