package software.ulpgc.chess.gameservice.infrastructure.adapters;

import software.ulpgc.chess.application.domain.model.*;
import software.ulpgc.chess.gameservice.application.ports.RoomRepository;
import software.ulpgc.chess.gameservice.application.ports.RoomCreationStore;
import software.ulpgc.chess.gameservice.application.domain.model.RoomCreation;

import java.sql.*;
import java.util.Objects;
import java.util.Optional;

public final class SqliteRoomRepository implements RoomRepository, RoomCreationStore {
    private final SqliteDatabase database;
    private final SqliteGameHistory history;

    public SqliteRoomRepository(SqliteDatabase database) {
        this.database = Objects.requireNonNull(database);
        this.history = new SqliteGameHistory();
    }

    @Override public Optional<Room> findByCode(String code) {
        if (code == null || code.isBlank()) throw new IllegalArgumentException("Room code cannot be empty");
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                Optional<Room> room = readRoom(connection, code);
                connection.commit();
                return room;
            } catch (SQLException | IllegalArgumentException exception) {
                rollback(connection, exception);
                throw exception;
            }
        } catch (SQLException | IllegalArgumentException exception) {
            throw new IllegalStateException("Could not load the room.", exception);
        }
    }

    private Optional<Room> readRoom(Connection connection, String code) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT game_id, white_id, white_name, black_id, black_name FROM rooms WHERE code = ?
                """)) {
            query.setString(1, code);
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) return Optional.empty();
                String gameId = rows.getString("game_id");
                User white = new User(rows.getString("white_id"), rows.getString("white_name"));
                String blackId = rows.getString("black_id");
                User black = blackId == null ? null : new User(blackId, rows.getString("black_name"));
                return Optional.of(new Room(code, white, black, history.readGame(connection, code, gameId)));
            }
        }
    }

    @Override public void save(Room room) {
        if (room == null) throw new IllegalArgumentException("Room cannot be null");
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                saveRoom(connection, room);
                history.saveMoves(connection, room.code(), room.game().moves());
                connection.commit();
            } catch (SQLException exception) {
                rollback(connection, exception);
                throw exception;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not save the room.", exception);
        }
    }

    @Override public Room createOnce(RoomCreation creation) {
        if (creation == null) throw new IllegalArgumentException("Room creation cannot be null.");
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                boolean inserted = insertInitialRoom(connection, creation.room());
                var previous = readCreation(connection, creation.operationKey());
                Room result;
                if (previous.isPresent()) {
                    if (!previous.orElseThrow().equals(creation))
                        throw new IllegalArgumentException("Creation key was already used for a different request.");
                    result = readCreatedRoom(connection, creation);
                } else {
                    if (!inserted) throw new IllegalArgumentException("Room already exists: " + creation.room().code());
                    saveCreation(connection, creation);
                    result = creation.room();
                }
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                rollback(connection, exception);
                throw exception;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not create the room.", exception);
        }
    }

    private boolean insertInitialRoom(Connection connection, Room room) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO rooms (code, game_id, white_id, white_name, black_id, black_name) VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(code) DO NOTHING
                """)) {
            bindRoom(insert, room);
            return insert.executeUpdate() == 1;
        }
    }

    private Room readCreatedRoom(Connection connection, RoomCreation creation) throws SQLException {
        try {
            Room stored = readRoom(connection, creation.room().code())
                    .orElseThrow(() -> new SQLException("The created room is missing."));
            creation.validateStoredRoom(stored);
            return stored;
        } catch (IllegalArgumentException exception) {
            throw new SQLException("Stored room is invalid.", exception);
        }
    }

    private Optional<RoomCreation> readCreation(Connection connection, String operationKey) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT room_code, game_id, creator_id, creator_name FROM room_creations WHERE operation_key = ?")) {
            query.setString(1, operationKey);
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) return Optional.empty();
                var creator = new User(rows.getString("creator_id"), rows.getString("creator_name"));
                var room = new Room(rows.getString("room_code"), creator, null, Game.initial(rows.getString("game_id")));
                return Optional.of(new RoomCreation(operationKey, room));
            }
        } catch (IllegalArgumentException exception) {
            throw new SQLException("Stored room creation is invalid.", exception);
        }
    }

    private void saveCreation(Connection connection, RoomCreation creation) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO room_creations (operation_key, room_code, game_id, creator_id, creator_name) VALUES (?, ?, ?, ?, ?)
                """)) {
            insert.setString(1, creation.operationKey());
            insert.setString(2, creation.room().code());
            insert.setString(3, creation.room().game().id());
            insert.setString(4, creation.room().white().id());
            insert.setString(5, creation.room().white().name());
            insert.executeUpdate();
        }
    }

    private void saveRoom(Connection connection, Room room) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO rooms (code, game_id, white_id, white_name, black_id, black_name) VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(code) DO UPDATE SET game_id = excluded.game_id,
                    white_id = excluded.white_id, white_name = excluded.white_name,
                    black_id = excluded.black_id, black_name = excluded.black_name
                """)) {
            bindRoom(statement, room);
            statement.executeUpdate();
        }
    }

    private void bindRoom(PreparedStatement statement, Room room) throws SQLException {
        User black = room.black();
        statement.setString(1, room.code());
        statement.setString(2, room.game().id());
        statement.setString(3, room.white().id());
        statement.setString(4, room.white().name());
        statement.setString(5, black == null ? null : black.id());
        statement.setString(6, black == null ? null : black.name());
    }

    private void rollback(Connection connection, Exception failure) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }
}
