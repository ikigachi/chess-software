package software.ulpgc.chess.gameservice.infrastructure.adapters;

import software.ulpgc.chess.application.domain.model.*;
import software.ulpgc.chess.gameservice.application.ports.RoomRepository;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class SqliteRoomRepository implements RoomRepository {
    private final SqliteDatabase database;

    public SqliteRoomRepository(SqliteDatabase database) {
        this.database = Objects.requireNonNull(database);
    }

    @Override public Optional<Room> findByCode(String code) {
        if (code == null || code.isBlank()) throw new IllegalArgumentException("Room code cannot be empty");
        try (Connection connection = database.openConnection();
             PreparedStatement query = connection.prepareStatement("""
                     SELECT game_id, white_id, white_name, black_id, black_name, ply, origin, destination, promotion
                     FROM rooms LEFT JOIN moves ON rooms.code = moves.room_code
                     WHERE rooms.code = ? ORDER BY moves.ply
                     """)) {
            query.setString(1, code);
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) return Optional.empty();
                return Optional.of(readRoom(code, rows));
            }
        } catch (SQLException | IllegalArgumentException exception) {
            throw new IllegalStateException("Could not load the room.", exception);
        }
    }

    private Room readRoom(String code, ResultSet rows) throws SQLException {
        String gameId = rows.getString("game_id");
        User white = new User(rows.getString("white_id"), rows.getString("white_name"));
        String blackId = rows.getString("black_id");
        User black = blackId == null ? null : new User(blackId, rows.getString("black_name"));
        return new Room(code, white, black, Game.replay(gameId, readMoves(rows)));
    }

    private List<Move> readMoves(ResultSet rows) throws SQLException {
        if (rows.getObject("ply") == null) return List.of();
        List<Move> moves = new ArrayList<>();
        do {
            if (rows.getInt("ply") != moves.size() + 1)
                throw new SQLException("Stored move history must start at 1 and have no gaps.");
            String origin = rows.getString("origin");
            String promotion = rows.getString("promotion");
            moves.add(new Move(Square.at(origin), Square.at(rows.getString("destination")),
                    promotion == null ? null : Promotion.valueOf(promotion)));
        } while (rows.next());
        return moves;
    }

    @Override public void save(Room room) {
        if (room == null) throw new IllegalArgumentException("Room cannot be null");
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                saveRoom(connection, room);
                saveMoves(connection, room.code(), room.game().moves());
                connection.commit();
            } catch (SQLException exception) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    exception.addSuppressed(rollbackFailure);
                }
                throw exception;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not save the room.", exception);
        }
    }

    private void saveRoom(Connection connection, Room room) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO rooms (code, game_id, white_id, white_name, black_id, black_name) VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(code) DO UPDATE SET game_id = excluded.game_id,
                    white_id = excluded.white_id, white_name = excluded.white_name,
                    black_id = excluded.black_id, black_name = excluded.black_name
                """)) {
            User black = room.black();
            statement.setString(1, room.code());
            statement.setString(2, room.game().id());
            statement.setString(3, room.white().id());
            statement.setString(4, room.white().name());
            statement.setString(5, black == null ? null : black.id());
            statement.setString(6, black == null ? null : black.name());
            statement.executeUpdate();
        }
    }

    private void saveMoves(Connection connection, String code, List<Move> moves) throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement("DELETE FROM moves WHERE room_code = ?");
             PreparedStatement insert = connection.prepareStatement(
                     "INSERT INTO moves (room_code, ply, origin, destination, promotion) VALUES (?, ?, ?, ?, ?)")) {
            delete.setString(1, code);
            delete.executeUpdate();
            for (int index = 0; index < moves.size(); index++) {
                Move move = moves.get(index);
                insert.setString(1, code);
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
