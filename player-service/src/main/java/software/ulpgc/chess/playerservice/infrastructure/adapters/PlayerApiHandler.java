package software.ulpgc.chess.playerservice.infrastructure.adapters;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.Strictness;
import io.javalin.http.Context;
import software.ulpgc.chess.application.domain.model.User;
import software.ulpgc.chess.playerservice.application.usecases.ResolvePlayerUseCase;
import software.ulpgc.chess.playerservice.application.usecases.RenamePlayerUseCase;
import software.ulpgc.chess.playerservice.application.usecases.AccountProfileUseCases;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

public final class PlayerApiHandler {
    private final ResolvePlayerUseCase resolve;
    private final RenamePlayerUseCase rename;
    private final AccountProfileUseCases accounts;
    private final Gson gson = new GsonBuilder().setStrictness(Strictness.STRICT).create();

    public PlayerApiHandler(ResolvePlayerUseCase resolve, RenamePlayerUseCase rename) {
        this(resolve, rename, null);
    }

    public PlayerApiHandler(ResolvePlayerUseCase resolve, RenamePlayerUseCase rename, AccountProfileUseCases accounts) {
        this.resolve = Objects.requireNonNull(resolve);
        this.rename = Objects.requireNonNull(rename);
        this.accounts = accounts;
    }

    public void loadAccountProfile(Context context) {
        if (accounts == null) throw new SecurityException("Account profile access is not configured.");
        User user = accounts.loadProfile(readAccountToken(context));
        sendJsonResponse(context, 200, new PlayerView(user.id(), user.name()));
    }

    public void updateAccountProfile(Context context) throws IOException {
        if (accounts == null) throw new SecurityException("Account profile access is not configured.");
        JsonObject request = readJsonRequest(context);
        if (request == null) return;
        User user = accounts.updateProfile(readAccountToken(context), readOptionalString(request, "name"));
        sendJsonResponse(context, 200, new PlayerView(user.id(), user.name()));
    }

    private String readAccountToken(Context context) {
        String header = context.header("Authorization");
        if (header == null || !header.startsWith("Bearer ")) throw new SecurityException("Account session is required.");
        return header.substring(7);
    }

    public void resolvePlayer(Context context) throws IOException {
        JsonObject fields = readJsonRequest(context);
        if (fields == null) return;
        User user = resolve.execute(readOptionalString(fields, "credential"));
        sendJsonResponse(context, 200, new PlayerView(user.id(), user.name()));
    }

    public void renamePlayer(Context context) throws IOException {
        JsonObject fields = readJsonRequest(context);
        if (fields == null) return;
        User user = rename.execute(readOptionalString(fields, "credential"), readOptionalString(fields, "name"));
        sendJsonResponse(context, 200, new PlayerView(user.id(), user.name()));
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
        sendJsonResponse(context, status, new ErrorView(message));
    }

    private void sendJsonResponse(Context context, int status, Object value) {
        context.status(status);
        context.contentType("application/json; charset=UTF-8");
        context.header("Cache-Control", "no-store");
        context.header("X-Content-Type-Options", "nosniff");
        context.result(gson.toJson(value));
    }

    private record PlayerView(String id, String name) {}
    private record ErrorView(String message) {}
}
