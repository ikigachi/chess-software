package software.ulpgc.chess.playerservice.infrastructure.adapters;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.Strictness;
import io.javalin.http.Context;
import software.ulpgc.chess.application.domain.model.User;
import software.ulpgc.chess.playerservice.application.usecases.ResolvePlayerUseCase;
import software.ulpgc.chess.playerservice.application.usecases.RenamePlayerUseCase;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

public final class PlayerApiHandler {
    private final ResolvePlayerUseCase resolve;
    private final RenamePlayerUseCase rename;
    private final Gson gson = new GsonBuilder().setStrictness(Strictness.STRICT).create();

    public PlayerApiHandler(ResolvePlayerUseCase resolve, RenamePlayerUseCase rename) {
        this.resolve = Objects.requireNonNull(resolve);
        this.rename = Objects.requireNonNull(rename);
    }

    public void resolve(Context context) throws IOException {
        JsonObject fields = fields(context);
        if (fields == null) return;
        User user = resolve.execute(string(fields, "credential"));
        json(context, 200, new PlayerView(user.id(), user.name()));
    }

    public void rename(Context context) throws IOException {
        JsonObject fields = fields(context);
        if (fields == null) return;
        User user = rename.execute(string(fields, "credential"), string(fields, "name"));
        json(context, 200, new PlayerView(user.id(), user.name()));
    }

    private JsonObject fields(Context context) throws IOException {
        String contentType = context.header("Content-Type");
        if (contentType == null || !contentType.split(";", 2)[0].strip().equalsIgnoreCase("application/json")) {
            error(context, 415, "Expected application/json.");
            return null;
        }
        byte[] body = context.bodyInputStream().readNBytes(8193);
        if (body.length > 8192) {
            error(context, 413, "Request too large");
            return null;
        }
        JsonObject fields = gson.fromJson(new String(body, StandardCharsets.UTF_8), JsonObject.class);
        if (fields == null) throw new IllegalArgumentException("Expected a JSON object.");
        return fields;
    }

    private String string(JsonObject fields, String name) {
        var value = fields.get(name);
        if (value == null || value.isJsonNull()) return null;
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
            throw new IllegalArgumentException("Expected a string for " + name + ".");
        return value.getAsString();
    }

    public void error(Context context, int status, String message) {
        json(context, status, new ErrorView(message));
    }

    private void json(Context context, int status, Object value) {
        context.status(status);
        context.contentType("application/json; charset=UTF-8");
        context.header("Cache-Control", "no-store");
        context.header("X-Content-Type-Options", "nosniff");
        context.result(gson.toJson(value));
    }

    private record PlayerView(String id, String name) {}
    private record ErrorView(String message) {}
}
