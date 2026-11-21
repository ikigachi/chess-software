package software.ulpgc.chess.registerservice.infrastructure.adapters;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.Strictness;
import io.javalin.http.Context;
import software.ulpgc.chess.registerservice.application.domain.model.Account;
import software.ulpgc.chess.registerservice.application.usecases.AccountUseCases;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

public final class AccountApiHandler {
    private final AccountUseCases accounts;
    private final Gson gson = new GsonBuilder().setStrictness(Strictness.STRICT).create();

    public AccountApiHandler(AccountUseCases accounts) {
        this.accounts = Objects.requireNonNull(accounts);
    }

    public void registerAccount(Context context) throws IOException {
        JsonObject request = readJsonRequest(context);
        if (request == null) return;
        Account account = accounts.registerAccount(readOptionalString(request, "credential"),
                readOptionalString(request, "username"), readOptionalString(request, "password"));
        sendAccountResponse(context, 201, account);
    }

    public void deactivateAccount(Context context) throws IOException {
        JsonObject request = readJsonRequest(context);
        if (request == null) return;
        Account account = accounts.deactivateAccount(readOptionalString(request, "credential"),
                readOptionalString(request, "password"));
        sendAccountResponse(context, 200, account);
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
        JsonObject request = gson.fromJson(new String(body, StandardCharsets.UTF_8), JsonObject.class);
        if (request == null) throw new IllegalArgumentException("Expected a JSON object.");
        return request;
    }

    private String readOptionalString(JsonObject request, String name) {
        var value = request.get(name);
        if (value == null || value.isJsonNull()) return null;
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
            throw new IllegalArgumentException("Expected a string for " + name + ".");
        return value.getAsString();
    }

    private void sendAccountResponse(Context context, int status, Account account) {
        sendJsonResponse(context, status, new AccountView(account.id(), account.username(), account.playerId(), account.status().name()));
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

    private record AccountView(String id, String username, String playerId, String status) {}
    private record ErrorView(String message) {}
}
