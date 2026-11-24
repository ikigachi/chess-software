package software.ulpgc.chess.gameservice.infrastructure.adapters;

import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.http.HandlerType;
import software.ulpgc.chess.application.domain.model.User;
import software.ulpgc.chess.gameservice.application.usecases.PlayerProfileUseCases;
import software.ulpgc.chess.gameservice.infrastructure.http.ApiMessages;
import software.ulpgc.chess.gameservice.infrastructure.http.HttpResponses;
import software.ulpgc.chess.gameservice.infrastructure.http.RoomJsonCodec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

public final class PlayerProfileApiHandler implements Handler {
    private final PlayerProfileUseCases profiles;
    private final BrowserSessions sessions;
    private final RoomJsonCodec codec;

    public PlayerProfileApiHandler(PlayerProfileUseCases profiles, BrowserSessions sessions, RoomJsonCodec codec) {
        this.profiles = Objects.requireNonNull(profiles);
        this.sessions = Objects.requireNonNull(sessions);
        this.codec = Objects.requireNonNull(codec);
    }

    @Override public void handle(Context context) throws IOException {
        var session = sessions.findActiveSession(context).orElse(null);
        if (session == null) {
            sendErrorResponse(context, 401, "Open the board to start a session.", null);
            return;
        }
        try {
            User profile;
            if (context.method().equals(HandlerType.GET)) {
                profile = session.accountToken == null ? profiles.loadPlayerProfile(session.user, session.credential)
                        : profiles.loadAccountProfile(session.user, session.accountToken);
            } else {
                var request = readProfileUpdateRequest(context, session);
                if (request == null) return;
                if (!session.token.equals(request.token())) {
                    sendErrorResponse(context, 403, ApiMessages.INVALID_TOKEN, session);
                    return;
                }
                profile = session.accountToken == null ? profiles.updatePlayerDisplayName(session.user, session.credential, request.name())
                        : profiles.updateAccountProfile(session.user, session.accountToken, request.name());
            }
            session.user = profile;
            sessions.markSessionActive(session);
            HttpResponses.json(context, 200, codec.serializePlayerProfile(profile, session.token));
        } catch (SecurityException exception) {
            sendErrorResponse(context, 401, "Sign in again to continue.", null);
        } catch (IllegalArgumentException exception) {
            sendErrorResponse(context, 400, exception.getMessage(), session);
        } catch (IllegalStateException exception) {
            sendErrorResponse(context, 503, ApiMessages.UNAVAILABLE, session);
        } catch (RuntimeException exception) {
            sendErrorResponse(context, 500, ApiMessages.FAILED, session);
        }
    }

    private RoomJsonCodec.UpdatePlayerProfileRequest readProfileUpdateRequest(Context context, BrowserSessions.Session session)
            throws IOException {
        String contentType = context.header("Content-Type");
        if (contentType == null || !contentType.split(";", 2)[0].strip().equalsIgnoreCase("application/json")) {
            sendErrorResponse(context, 415, "Expected application/json.", session);
            return null;
        }
        byte[] body = context.bodyInputStream().readNBytes(8193);
        if (body.length > 8192) {
            sendErrorResponse(context, 413, ApiMessages.REQUEST_TOO_LARGE, session);
            return null;
        }
        return codec.parsePlayerProfileUpdateRequest(new String(body, StandardCharsets.UTF_8));
    }

    private void sendErrorResponse(Context context, int status, String message, BrowserSessions.Session session) {
        HttpResponses.json(context, status, codec.error(message, session == null ? null : session.token));
    }
}
