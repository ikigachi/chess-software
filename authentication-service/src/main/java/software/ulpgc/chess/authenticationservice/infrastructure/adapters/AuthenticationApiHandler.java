package software.ulpgc.chess.authenticationservice.infrastructure.adapters;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.Strictness;
import io.javalin.http.Context;
import software.ulpgc.chess.authenticationservice.application.domain.model.AuthenticatedSession;
import software.ulpgc.chess.authenticationservice.application.usecases.AuthenticationUseCases;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

public final class AuthenticationApiHandler {
    private final AuthenticationUseCases authentication;
    private final Gson gson = new GsonBuilder().setStrictness(Strictness.STRICT).create();

    public AuthenticationApiHandler(AuthenticationUseCases authentication) {
        this.authentication = Objects.requireNonNull(authentication);
    }

    public void login(Context context) throws IOException {
        JsonObject fields = readJsonRequest(context);
        if (fields == null) return;
        var grant = authentication.login(readOptionalString(fields, "username"), readOptionalString(fields, "password"));
        var session = grant.session();
        sendJsonResponse(context, 201, new LoginView(grant.token(), session.accountId(), session.playerId(), session.expiresAt().toString()));
    }

    public void resumeSession(Context context) {
        AuthenticatedSession session = authentication.resumeSession(readBearerToken(context));
        sendJsonResponse(context, 200, new SessionView(session.accountId(), session.playerId(), session.expiresAt().toString()));
    }

    public void logout(Context context) {
        authentication.logout(readBearerToken(context));
        context.header("Cache-Control", "no-store");
        context.header("X-Content-Type-Options", "nosniff");
        context.status(204);
    }

    private String readBearerToken(Context context) {
        String authorization = context.header("Authorization");
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7))
            throw new SecurityException("Session is invalid or expired.");
        return authorization.substring(7);
    }

    private JsonObject readJsonRequest(Context context) throws IOException {
        String contentType = context.header("Content-Type");
        if (contentType == null || !contentType.split(";", 2)[0].strip().equalsIgnoreCase("application/json")) {
            sendErrorResponse(context, 415, "Expected application/json.");
            return null;
        }
        byte[] body = context.bodyInputStream().readNBytes(8193);
        if (body.length > 8192) {
            sendErrorResponse(context, 413, "Request too large");
            return null;
        }
        JsonObject fields = gson.fromJson(new String(body, StandardCharsets.UTF_8), JsonObject.class);
        if (fields == null) throw new IllegalArgumentException("Expected a JSON object.");
        return fields;
    }

    private String readOptionalString(JsonObject fields, String name) {
        var value = fields.get(name);
        if (value == null || value.isJsonNull()) return null;
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
            throw new IllegalArgumentException("Expected a string for " + name + ".");
        return value.getAsString();
    }

    public void sendErrorResponse(Context context, int status, String message) {
        if (status == 401) context.header("WWW-Authenticate", "Bearer");
        sendJsonResponse(context, status, new ErrorView(message));
    }

    private void sendJsonResponse(Context context, int status, Object value) {
        context.status(status);
        context.contentType("application/json; charset=UTF-8");
        context.header("Cache-Control", "no-store");
        context.header("X-Content-Type-Options", "nosniff");
        context.result(gson.toJson(value));
    }

    private record LoginView(String token, String accountId, String playerId, String expiresAt) {}
    private record SessionView(String accountId, String playerId, String expiresAt) {}
    private record ErrorView(String message) {}
}
