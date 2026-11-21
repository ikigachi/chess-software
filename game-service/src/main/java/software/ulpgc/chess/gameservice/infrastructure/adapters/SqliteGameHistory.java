package software.ulpgc.chess.gameservice.infrastructure.adapters;

import software.ulpgc.chess.application.domain.model.Game;
import software.ulpgc.chess.application.domain.model.Move;
import software.ulpgc.chess.application.domain.model.Promotion;
import software.ulpgc.chess.application.domain.model.Square;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

final class SqliteGameHistory {
    Game readGame(Connection connection, String roomCode, String gameId) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT ply, origin, destination, promotion FROM moves
                WHERE room_code = ? ORDER BY ply
                """)) {
            query.setString(1, roomCode);
            try (ResultSet rows = query.executeQuery()) {
                return Game.replay(gameId, readMoves(rows));
            }
        }
    }

    private List<Move> readMoves(ResultSet rows) throws SQLException {
        List<Move> moves = new ArrayList<>();
        while (rows.next()) {
            if (rows.getInt("ply") != moves.size() + 1)
                throw new SQLException("Stored move history must start at 1 and have no gaps.");
            String promotion = rows.getString("promotion");
            moves.add(new Move(Square.at(rows.getString("origin")), Square.at(rows.getString("destination")),
                    promotion == null ? null : Promotion.valueOf(promotion)));
        }
        return moves;
    }

    void saveMoves(Connection connection, String roomCode, List<Move> moves) throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement("DELETE FROM moves WHERE room_code = ?");
             PreparedStatement insert = connection.prepareStatement(
                     "INSERT INTO moves (room_code, ply, origin, destination, promotion) VALUES (?, ?, ?, ?, ?)")) {
            delete.setString(1, roomCode);
            delete.executeUpdate();
            for (int index = 0; index < moves.size(); index++) {
                Move move = moves.get(index);
                insert.setString(1, roomCode);
                insert.setInt(2, index + 1);
                insert.setString(3, move.from().coordinate());
                insert.setString(4, move.to().coordinate());
                insert.setString(5, move.promotion() == null ? null : move.promotion().name());
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }
}
