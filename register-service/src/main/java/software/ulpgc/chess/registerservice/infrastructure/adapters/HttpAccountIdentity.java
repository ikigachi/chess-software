package software.ulpgc.chess.registerservice.infrastructure.adapters;

import com.google.gson.*;
import software.ulpgc.chess.registerservice.application.ports.AccountIdentity;

import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;

public final class HttpAccountIdentity implements AccountIdentity, AutoCloseable {
    private final URI origin;
    private final HttpClient client;
    private final Gson gson = new GsonBuilder().setStrictness(Strictness.STRICT).create();

    public HttpAccountIdentity(URI origin) {
        if (origin == null || !Set.of("http", "https").contains(origin.getScheme()) || origin.getHost() == null
                || origin.getRawUserInfo() != null || origin.getRawQuery() != null || origin.getRawFragment() != null
                || !Set.of("", "/").contains(origin.getPath()))
            throw new IllegalArgumentException("Authentication service must be an HTTP origin.");
        this.origin = origin;
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    }

    @Override public String resolvePlayerId(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) throw new SecurityException("Invalid account session.");
        var request = HttpRequest.newBuilder(origin.resolve("/sessions/current")).timeout(Duration.ofSeconds(4))
                .header("Authorization", "Bearer " + token).GET().build();
        try {
            var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (var input = response.body()) {
                if (response.statusCode() == 401) throw new SecurityException("Invalid or expired account session.");
                if (response.statusCode() != 200) throw new IOException("Authentication service rejected the request.");
                byte[] body = input.readNBytes(8193);
                if (body.length > 8192) throw new IOException("Authentication response is too large.");
                JsonObject fields = gson.fromJson(new String(body, StandardCharsets.UTF_8), JsonObject.class);
                var id = fields == null ? null : fields.get("playerId");
                if (id == null || !id.isJsonPrimitive() || !id.getAsJsonPrimitive().isString() || id.getAsString().isBlank())
                    throw new IOException("Authentication response has no player identity.");
                return id.getAsString();
            }
        } catch (IOException | JsonParseException exception) {
            throw new IllegalStateException("Authentication service is unavailable.", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Authentication request was interrupted.", exception);
        }
    }

    @Override public void close() { client.close(); }
}
