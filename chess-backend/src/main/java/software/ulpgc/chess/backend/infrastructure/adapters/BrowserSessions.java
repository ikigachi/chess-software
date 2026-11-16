package software.ulpgc.chess.backend.infrastructure.adapters;

import com.sun.net.httpserver.HttpExchange;
import software.ulpgc.chess.application.domain.model.User;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

public final class BrowserSessions {
    private final Map<String, Session> sessions = new HashMap<>();

    Session get(HttpExchange exchange) {
        String cookie = exchange.getRequestHeaders().getFirst("Cookie");
        String credential = null;
        if (cookie != null) for (String part : cookie.split(";")) {
            String[] pair = part.strip().split("=", 2);
            if (pair.length == 2 && pair[0].equals("regalia")) credential = pair[1];
        }
        sessions.values().removeIf(value -> System.currentTimeMillis() - value.lastSeen > 8 * 60 * 60 * 1000L);
        boolean issueCookie = !isSessionCredential(credential);
        if (issueCookie) credential = UUID.randomUUID().toString();
        Session session = sessions.get(credential);
        if (session == null) {
            if (sessions.size() >= 512) throw new IllegalStateException("Too many local sessions");
            session = new Session(playerId(credential));
            sessions.put(credential, session);
        }
        if (issueCookie) {
            exchange.getResponseHeaders().add("Set-Cookie", "regalia=" + credential + "; Path=/; HttpOnly; SameSite=Lax");
        }
        session.lastSeen = System.currentTimeMillis();
        return session;
    }

    private static boolean isSessionCredential(String value) {
        if (value == null || value.length() != 36) return false;
        try {
            return value.equals(UUID.fromString(value).toString());
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static String playerId(String credential) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(credential.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Could not derive the browser identity.", exception);
        }
    }

    static final class Session {
        final String token = UUID.randomUUID().toString();
        User user;
        String code;
        long lastSeen = System.currentTimeMillis();
        Session(String id) { user = new User(id, "Guest"); }
    }
}
