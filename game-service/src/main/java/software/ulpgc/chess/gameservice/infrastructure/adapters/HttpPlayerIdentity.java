package software.ulpgc.chess.gameservice.infrastructure.adapters;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.JsonObject;
import com.google.gson.Strictness;
import software.ulpgc.chess.application.domain.model.User;
import software.ulpgc.chess.gameservice.application.ports.PlayerIdentity;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;

public final class HttpPlayerIdentity implements PlayerIdentity, AutoCloseable {
    private final URI endpoint;
    private final HttpClient client;
    private final Gson gson = new GsonBuilder().setStrictness(Strictness.STRICT).create();

    public HttpPlayerIdentity(URI service) {
        if (service == null || !Set.of("http", "https").contains(service.getScheme()) || service.getHost() == null
                || service.getRawUserInfo() != null || service.getRawQuery() != null || service.getRawFragment() != null
                || !Set.of("", "/").contains(service.getPath()))
            throw new IllegalArgumentException("Player service must be an HTTP origin.");
        endpoint = service.resolve("/players/resolve");
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    }

    @Override public User resolve(String credential) {
        var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(4))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(new ResolveRequest(credential)), StandardCharsets.UTF_8)).build();
        try {
            var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (var input = response.body()) {
                if (response.statusCode() != 200) throw new IOException("Player service returned an unsuccessful response.");
                byte[] body = input.readNBytes(8193);
                if (body.length > 8192) throw new IOException("Player service response is too large.");
                JsonObject player = gson.fromJson(new String(body, StandardCharsets.UTF_8), JsonObject.class);
                if (player == null) throw new IOException("Missing player service response.");
                return new User(readRequiredString(player, "id"), readRequiredString(player, "name"));
            }
        } catch (IOException | JsonParseException | IllegalArgumentException exception) {
            throw new IllegalStateException("Player service is unavailable.", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Player service request was interrupted.", exception);
        }
    }

    private String readRequiredString(JsonObject fields, String name) throws IOException {
        var value = fields.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
            throw new IOException("Invalid player service response.");
        return value.getAsString();
    }

    @Override public void close() { client.close(); }

    private record ResolveRequest(String credential) {}
}
