package software.ulpgc.chess.authenticationservice.infrastructure.adapters;

import software.ulpgc.chess.authenticationservice.application.domain.model.AuthenticatedSession;
import software.ulpgc.chess.authenticationservice.application.ports.SessionRepository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public final class SqliteSessionRepository implements SessionRepository {
    private final SqliteAuthenticationDatabase database;

    public SqliteSessionRepository(SqliteAuthenticationDatabase database) {
        this.database = Objects.requireNonNull(database);
    }

    @Override public void create(String tokenHash, AuthenticatedSession session, Instant idleCutoff) {
        validateTokenHash(tokenHash);
        Objects.requireNonNull(session);
        Objects.requireNonNull(idleCutoff);
        inTransaction("Could not create the session.", connection -> {
            deleteExpiredSessions(connection, session.createdAt(), idleCutoff);
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO authentication_sessions (token_hash, account_id, created_at, last_seen_at, expires_at)
                    SELECT ?, id, ?, ?, ? FROM accounts WHERE id = ? AND player_id = ? AND status = 'Active'
                    """)) {
                insert.setString(1, tokenHash);
                insert.setLong(2, session.createdAt().toEpochMilli());
                insert.setLong(3, session.lastSeenAt().toEpochMilli());
                insert.setLong(4, session.expiresAt().toEpochMilli());
                insert.setString(5, session.accountId());
                insert.setString(6, session.playerId());
                if (insert.executeUpdate() == 0) throw new SecurityException("Account credentials are invalid.");
            }
            return null;
        });
    }

    @Override public Optional<AuthenticatedSession> resume(String tokenHash, Instant now, Instant idleCutoff) {
        validateTokenHash(tokenHash);
        Objects.requireNonNull(now);
        Objects.requireNonNull(idleCutoff);
        return inTransaction("Could not resume the session.", connection -> {
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE authentication_sessions SET last_seen_at = ?
                    WHERE token_hash = ? AND expires_at > ? AND last_seen_at > ? AND last_seen_at <= ?
                        AND EXISTS (SELECT 1 FROM accounts
                            WHERE accounts.id = authentication_sessions.account_id AND accounts.status = 'Active')
                    """)) {
                update.setLong(1, now.toEpochMilli());
                update.setString(2, tokenHash);
                update.setLong(3, now.toEpochMilli());
                update.setLong(4, idleCutoff.toEpochMilli());
                update.setLong(5, now.toEpochMilli());
                if (update.executeUpdate() == 0) return Optional.empty();
            }
            try (PreparedStatement query = connection.prepareStatement("""
                    SELECT authentication_sessions.account_id, accounts.player_id, created_at, last_seen_at, expires_at
                    FROM authentication_sessions JOIN accounts ON accounts.id = authentication_sessions.account_id
                    WHERE token_hash = ?
                    """)) {
                query.setString(1, tokenHash);
                try (ResultSet rows = query.executeQuery()) {
                    if (!rows.next()) throw new SQLException("Session disappeared during a transaction.");
                    return Optional.of(readSession(rows));
                }
            }
        });
    }

    @Override public void revoke(String tokenHash) {
        validateTokenHash(tokenHash);
        try (Connection connection = database.openConnection();
             PreparedStatement delete = connection.prepareStatement("DELETE FROM authentication_sessions WHERE token_hash = ?")) {
            delete.setString(1, tokenHash);
            delete.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not revoke the session.", exception);
        }
    }

    private void deleteExpiredSessions(Connection connection, Instant now, Instant idleCutoff) throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement("""
                DELETE FROM authentication_sessions WHERE expires_at <= ? OR last_seen_at <= ?
                    OR account_id IN (SELECT id FROM accounts WHERE status = 'Inactive')
                """)) {
            delete.setLong(1, now.toEpochMilli());
            delete.setLong(2, idleCutoff.toEpochMilli());
            delete.executeUpdate();
        }
    }

    private AuthenticatedSession readSession(ResultSet rows) throws SQLException {
        return new AuthenticatedSession(rows.getString("account_id"), rows.getString("player_id"),
                Instant.ofEpochMilli(rows.getLong("created_at")), Instant.ofEpochMilli(rows.getLong("last_seen_at")),
                Instant.ofEpochMilli(rows.getLong("expires_at")));
    }

    private void validateTokenHash(String tokenHash) {
        if (tokenHash == null || !tokenHash.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("Session token hash is invalid.");
    }

    private <T> T inTransaction(String failureMessage, SqlOperation<T> operation) {
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                T result = operation.execute(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    exception.addSuppressed(rollbackFailure);
                }
                throw exception;
            }
        } catch (SQLException | IllegalArgumentException exception) {
            throw new IllegalStateException(failureMessage, exception);
        }
    }

    private interface SqlOperation<T> {
        T execute(Connection connection) throws SQLException;
    }
}
