package software.ulpgc.chess.backend.infrastructure.adapters;

import com.sun.net.httpserver.HttpExchange;
import software.ulpgc.chess.application.domain.model.User;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class BrowserSessions {
    private final Map<String, Session> sessions = new HashMap<>();

    Session get(HttpExchange exchange) {
        String cookie = exchange.getRequestHeaders().getFirst("Cookie");
        String id = null;
        if (cookie != null) for (String part : cookie.split(";")) {
            String[] pair = part.strip().split("=", 2);
            if (pair.length == 2 && pair[0].equals("regalia")) id = pair[1];
        }
        sessions.values().removeIf(value -> System.currentTimeMillis() - value.lastSeen > 8 * 60 * 60 * 1000L);
        if (id == null || !sessions.containsKey(id)) {
            if (sessions.size() >= 512) throw new IllegalStateException("Too many local sessions");
            id = UUID.randomUUID().toString();
            sessions.put(id, new Session(id));
            exchange.getResponseHeaders().add("Set-Cookie", "regalia=" + id + "; Path=/; HttpOnly; SameSite=Lax");
        }
        Session session = sessions.get(id);
        session.lastSeen = System.currentTimeMillis();
        return session;
    }

    static final class Session {
        final String token = UUID.randomUUID().toString();
        User user;
        String code;
        long lastSeen = System.currentTimeMillis();
        Session(String id) { user = new User(id, "Guest"); }
    }
}
