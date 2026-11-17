package software.ulpgc.chess.playerservice.infrastructure.adapters;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.Strictness;
import io.javalin.http.Context;
import software.ulpgc.chess.application.domain.model.User;
import software.ulpgc.chess.playerservice.application.usecases.ResolvePlayerUseCase;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

public final class PlayerApiHandler {
    private final ResolvePlayerUseCase resolve;
    private final Gson gson = new GsonBuilder().setStrictness(Strictness.STRICT).create();

    public PlayerApiHandler(ResolvePlayerUseCase resolve) {
        this.resolve = Objects.requireNonNull(resolve);
    }

    public void resolve(Context context) throws IOException {
        String contentType = context.header("Content-Type");
        if (contentType == null || !contentType.split(";", 2)[0].strip().equalsIgnoreCase("application/json")) {
            error(context, 415, "Expected application/json.");
            return;
        }
        byte[] body = context.bodyInputStream().readNBytes(8193);
        if (body.length > 8192) {
            error(context, 413, "Request too large");
            return;
        }
        JsonObject fields = gson.fromJson(new String(body, StandardCharsets.UTF_8), JsonObject.class);
        if (fields == null || !fields.has("credential") || !fields.get("credential").isJsonPrimitive()
                || !fields.getAsJsonPrimitive("credential").isString())
            throw new IllegalArgumentException("Expected a player credential.");
        User user = resolve.execute(fields.get("credential").getAsString());
        json(context, 200, new PlayerView(user.id(), user.name()));
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
