package software.ulpgc.chess.gameservice.infrastructure.adapters;

import io.javalin.http.Context;
import software.ulpgc.chess.application.domain.model.User;
import software.ulpgc.chess.gameservice.application.ports.PlayerIdentity;

import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class BrowserSessions {
    private static final long SESSION_TIMEOUT = Duration.ofHours(8).toMillis();
    private static final long PRESENCE_TIMEOUT = Duration.ofSeconds(15).toMillis();

    private final Map<String, Session> sessions = new HashMap<>();
    private final PlayerIdentity players;
    private final Clock clock;

    public BrowserSessions(PlayerIdentity players) {
        this(players, Clock.systemUTC());
    }

    public BrowserSessions(PlayerIdentity players, Clock clock) {
        this.players = Objects.requireNonNull(players);
        this.clock = Objects.requireNonNull(clock);
    }

    Session get(Context context) {
        String cookie = context.header("Cookie");
        String credential = null;
        if (cookie != null) for (String part : cookie.split(";")) {
            String[] pair = part.strip().split("=", 2);
            if (pair.length == 2 && pair[0].equals("regalia")) credential = pair[1];
        }
        long now = clock.millis();
        sessions.values().removeIf(value -> now - value.lastSeen > SESSION_TIMEOUT);
        boolean issueCookie = !isSessionCredential(credential);
        if (issueCookie) credential = UUID.randomUUID().toString();
        Session session = sessions.get(credential);
        if (session == null) {
            if (sessions.size() >= 512) throw new IllegalStateException("Too many local sessions");
            session = new Session(players.resolve(credential));
            sessions.put(credential, session);
        }
        if (issueCookie) {
            context.addHeader("Set-Cookie", "regalia=" + credential + "; Path=/; HttpOnly; SameSite=Lax");
        }
        session.lastSeen = now;
        return session;
    }

    boolean isPresent(String code, User user) {
        if (user == null) return false;
        long now = clock.millis();
        for (Session session : sessions.values()) {
            if (code.equals(session.code) && user.id().equals(session.user.id())
                    && now - session.lastSeen < PRESENCE_TIMEOUT) return true;
        }
        return false;
    }

    private static boolean isSessionCredential(String value) {
        if (value == null || value.length() != 36) return false;
        try {
            return value.equals(UUID.fromString(value).toString());
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    static final class Session {
        final String token = UUID.randomUUID().toString();
        User user;
        String code;
        long lastSeen;
        Session(User user) { this.user = Objects.requireNonNull(user); }
    }
}
