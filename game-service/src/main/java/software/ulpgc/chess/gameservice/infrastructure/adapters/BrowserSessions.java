package software.ulpgc.chess.gameservice.infrastructure.adapters;

import io.javalin.http.Context;
import software.ulpgc.chess.application.domain.model.User;
import software.ulpgc.chess.gameservice.application.ports.AccountAuthentication;
import software.ulpgc.chess.gameservice.application.ports.PlayerIdentity;
import software.ulpgc.chess.gameservice.application.usecases.AccountAccessUseCases;

import java.time.Clock;
import java.time.Duration;
import java.util.*;

public final class BrowserSessions {
    private static final long SESSION_TIMEOUT = Duration.ofHours(8).toMillis();
    private static final long PRESENCE_TIMEOUT = Duration.ofSeconds(15).toMillis();
    private final Map<String, Session> sessions = new HashMap<>();
    private final PlayerIdentity players;
    private final AccountAccessUseCases accounts;
    private final Clock clock;

    public BrowserSessions(PlayerIdentity players, AccountAccessUseCases accounts) {
        this(players, accounts, Clock.systemUTC());
    }

    public BrowserSessions(PlayerIdentity players, AccountAccessUseCases accounts, Clock clock) {
        this.players = Objects.requireNonNull(players);
        this.accounts = Objects.requireNonNull(accounts);
        this.clock = Objects.requireNonNull(clock);
    }

    Session getOrCreateSession(Context context) {
        removeExpiredSessions();
        String credential = readCookie(context, "regalia");
        boolean issueCookie = !isCanonicalSessionCredential(credential);
        if (issueCookie) credential = UUID.randomUUID().toString();
        Session session = sessions.get(credential);
        if (session == null) session = new Session(credential);
        resolvePlayer(context, session);
        markSessionActive(session);
        storeSession(session);
        if (issueCookie) setGuestCookie(context, credential);
        return session;
    }

    Session prepareControlSession(Context context) {
        removeExpiredSessions();
        String credential = readCookie(context, "regalia");
        boolean issueCookie = !isCanonicalSessionCredential(credential);
        if (issueCookie) credential = UUID.randomUUID().toString();
        Session session = sessions.get(credential);
        if (session == null) session = new Session(credential);
        return session;
    }

    void acceptControlSession(Context context, Session session) {
        storeSession(session);
        if (!session.credential.equals(readCookie(context, "regalia"))) setGuestCookie(context, session.credential);
    }

    Optional<Session> findControlSession(Context context) {
        String credential = readCookie(context, "regalia");
        Session session = credential == null ? null : sessions.get(credential);
        return session == null || clock.millis() - session.lastSeen > SESSION_TIMEOUT ? Optional.empty() : Optional.of(session);
    }

    Optional<Session> findActiveSession(Context context) {
        Optional<Session> session = findControlSession(context);
        session.ifPresent(value -> resolvePlayer(context, value));
        return session;
    }

    void resolvePlayer(Context context, Session session) {
        String accountToken = readCookie(context, "regalia-account");
        if (accountToken == null) {
            if (session.accountToken != null) throw new SecurityException("Sign in again or explicitly sign out.");
            User user = session.user == null ? players.resolve(session.credential) : session.user;
            accounts.requireGuestAccess(user);
            session.user = user;
            return;
        }
        if (session.accountToken != null && !session.accountToken.equals(accountToken))
            throw new SecurityException("Account session has changed. Sign in again.");
        if (!accountToken.matches("[A-Za-z0-9_-]{43}")) throw new SecurityException("Invalid account session.");
        var identity = accounts.resumeAccount(accountToken);
        if (!identity.expiresAt().isAfter(clock.instant())) throw new SecurityException("Account session has expired.");
        if (session.accountToken != null && session.user != null && !session.user.id().equals(identity.playerId()))
            throw new IllegalStateException("Authentication service changed the session identity.");
        boolean samePlayer = session.user != null && session.user.id().equals(identity.playerId());
        User user = new User(identity.playerId(), samePlayer ? session.user.name() : "Guest");
        if (session.accountToken == null) {
            session.token = UUID.randomUUID().toString();
            if (!samePlayer) session.code = null;
        }
        session.user = user;
        session.accountToken = accountToken;
    }

    Session acceptLogin(Context context, Session previous, AccountAuthentication.Grant grant) {
        long maxAge = Duration.between(clock.instant(), grant.identity().expiresAt()).toSeconds();
        if (maxAge <= 0) throw new IllegalStateException("Authentication service issued an expired session.");
        Session session = new Session(UUID.randomUUID().toString());
        boolean samePlayer = previous.user != null && previous.user.id().equals(grant.identity().playerId());
        session.user = new User(grant.identity().playerId(), samePlayer ? previous.user.name() : "Guest");
        session.code = samePlayer ? previous.code : null;
        session.accountToken = grant.token();
        session.lastActive = clock.millis();
        sessions.remove(previous.credential);
        storeSession(session);
        setGuestCookie(context, session.credential);
        context.addHeader("Set-Cookie", "regalia-account=" + grant.token() + "; Path=/; HttpOnly; SameSite=Lax; Max-Age="
                + maxAge + secureAttribute(context));
        return session;
    }

    Session discardAccount(Context context, Session previous) {
        Session session = new Session(UUID.randomUUID().toString());
        sessions.remove(previous.credential);
        storeSession(session);
        setGuestCookie(context, session.credential);
        context.addHeader("Set-Cookie", "regalia-account=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0" + secureAttribute(context));
        return session;
    }

    String accountToken(Context context) { return readCookie(context, "regalia-account"); }

    void markSessionActive(Session session) {
        session.lastSeen = clock.millis();
        session.lastActive = session.lastSeen;
    }

    boolean isPresent(String code, User user) {
        if (user == null) return false;
        long now = clock.millis();
        for (Session session : sessions.values())
            if (session.user != null && code.equals(session.code) && user.id().equals(session.user.id())
                    && now - session.lastActive < PRESENCE_TIMEOUT) return true;
        return false;
    }

    private void removeExpiredSessions() {
        sessions.values().removeIf(value -> clock.millis() - value.lastSeen > SESSION_TIMEOUT);
    }

    private void storeSession(Session session) {
        if (!sessions.containsKey(session.credential) && sessions.size() >= 512)
            throw new IllegalStateException("Too many local sessions");
        session.lastSeen = clock.millis();
        sessions.put(session.credential, session);
    }

    private void setGuestCookie(Context context, String credential) {
        context.addHeader("Set-Cookie", "regalia=" + credential + "; Path=/; HttpOnly; SameSite=Lax" + secureAttribute(context));
    }

    private String secureAttribute(Context context) { return context.req().isSecure() ? "; Secure" : ""; }

    private String readCookie(Context context, String name) {
        String header = context.header("Cookie");
        String value = null;
        if (header != null) for (String part : header.split(";")) {
            String[] pair = part.strip().split("=", 2);
            if (pair.length != 2 || !pair[0].equals(name)) continue;
            String candidate = pair[1];
            if (candidate.length() >= 2 && candidate.startsWith("\"") && candidate.endsWith("\""))
                candidate = candidate.substring(1, candidate.length() - 1);
            if (value != null && !value.equals(candidate)) throw new SecurityException("Ambiguous session cookie.");
            value = candidate;
        }
        return value;
    }

    private static boolean isCanonicalSessionCredential(String value) {
        if (value == null || value.length() != 36) return false;
        try { return value.equals(UUID.fromString(value).toString()); }
        catch (IllegalArgumentException exception) { return false; }
    }

    static final class Session {
        String token = UUID.randomUUID().toString();
        final String credential;
        User user;
        String accountToken;
        String code;
        long lastSeen;
        long lastActive;
        Session(String credential) { this.credential = Objects.requireNonNull(credential); }
    }
}
