package software.ulpgc.chess.gameservice.infrastructure.adapters;

import com.google.gson.*;
import io.javalin.http.Context;
import software.ulpgc.chess.gameservice.application.usecases.AccountAccessUseCases;
import software.ulpgc.chess.gameservice.infrastructure.http.HttpResponses;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

public final class BrowserAccountApiHandler {
    private final AccountAccessUseCases accounts;
    private final BrowserSessions sessions;
    private final Gson gson = new GsonBuilder().serializeNulls().setStrictness(Strictness.STRICT).create();

    public BrowserAccountApiHandler(AccountAccessUseCases accounts, BrowserSessions sessions) {
        this.accounts = Objects.requireNonNull(accounts);
        this.sessions = Objects.requireNonNull(sessions);
    }

    public void loadSession(Context context) {
        var session = sessions.prepareControlSession(context);
        boolean requiresLogin = false;
        try {
            sessions.resolvePlayer(context, session);
            sessions.markSessionActive(session);
        }
        catch (SecurityException exception) { requiresLogin = true; }
        sessions.acceptControlSession(context, session);
        sendSession(context, 200, session, requiresLogin, null);
    }

    public void login(Context context) throws IOException {
        JsonObject request = readJsonRequest(context);
        if (request == null) return;
        var previous = requireControlSession(context, request);
        var grant = accounts.login(readString(request, "username"), readString(request, "password"));
        sendSession(context, 201, sessions.acceptLogin(context, previous, grant), false, "Signed in.");
    }

    public void logout(Context context) throws IOException {
        JsonObject request = readJsonRequest(context);
        if (request == null) return;
        var previous = requireControlSession(context, request);
        String token = previous.accountToken == null ? sessions.accountToken(context) : previous.accountToken;
        if (token != null) accounts.logout(token);
        sendSession(context, 200, sessions.discardAccount(context, previous), false, "Signed out.");
    }

    public void registerAccount(Context context) throws IOException {
        JsonObject request = readJsonRequest(context);
        if (request == null) return;
        var control = requireControlSession(context, request);
        var session = sessions.getOrCreateSession(context);
        if (session.accountToken != null) throw new SecurityException("Sign out before registering another account.");
        accounts.register(session.user, session.credential, readString(request, "username"), readString(request, "password"));
        sendSession(context, 201, control, true, "Account created. Sign in to continue.");
    }

    public void deactivateAccount(Context context) throws IOException {
        JsonObject request = readJsonRequest(context);
        if (request == null) return;
        requireControlSession(context, request);
        var session = sessions.getOrCreateSession(context);
        if (session.accountToken == null) throw new SecurityException("Sign in before deactivating an account.");
        accounts.deactivate(session.user, session.accountToken, readString(request, "password"));
        sendSession(context, 200, sessions.discardAccount(context, session), false, "Account deactivated.");
    }

    private BrowserSessions.Session requireControlSession(Context context, JsonObject request) {
        String origin = context.header("Origin");
        if (origin != null && !origin.equals(context.req().getScheme() + "://" + context.header("Host")))
            throw new InvalidBrowserTokenException();
        var session = sessions.findControlSession(context).orElseThrow(() -> new SecurityException("Load the session before continuing."));
        if (!session.token.equals(readString(request, "token"))) throw new InvalidBrowserTokenException();
        return session;
    }

    private JsonObject readJsonRequest(Context context) throws IOException {
        String type = context.header("Content-Type");
        if (type == null || !type.split(";", 2)[0].strip().equalsIgnoreCase("application/json")) {
            HttpResponses.json(context, 415, gson.toJson(new ErrorView("Expected application/json.")));
            return null;
        }
        byte[] body = context.bodyInputStream().readNBytes(8193);
        if (body.length > 8192) {
            HttpResponses.json(context, 413, gson.toJson(new ErrorView("Request too large")));
            return null;
        }
        try {
            JsonObject fields = gson.fromJson(new String(body, StandardCharsets.UTF_8), JsonObject.class);
            if (fields == null) throw new IllegalArgumentException("Expected a JSON object.");
            return fields;
        } catch (JsonParseException exception) { throw new IllegalArgumentException("Invalid JSON request.", exception); }
    }

    private String readString(JsonObject fields, String name) {
        var value = fields.get(name);
        if (value == null || value.isJsonNull()) return null;
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("Expected a string for " + name + ".");
        return value.getAsString();
    }

    private void sendSession(Context context, int status, BrowserSessions.Session session, boolean requiresLogin, String message) {
        Viewer viewer = requiresLogin || session.user == null ? null : new Viewer(session.user.id(), session.user.name());
        HttpResponses.json(context, status, gson.toJson(new SessionView(session.accountToken != null && !requiresLogin,
                requiresLogin, viewer, session.token, message)));
    }

    public static final class InvalidBrowserTokenException extends SecurityException {}
    private record Viewer(String id, String name) {}
    private record SessionView(boolean authenticated, boolean requiresLogin, Viewer viewer, String token, String message) {}
    private record ErrorView(String message) {}
}
