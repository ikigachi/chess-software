package software.ulpgc.chess.gameservice.infrastructure.adapters;

import com.google.gson.*;
import software.ulpgc.chess.gameservice.application.ports.AccountAuthentication;
import software.ulpgc.chess.gameservice.application.ports.AccountRegistration;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;

public final class HttpAccountServices implements AccountAuthentication, AccountRegistration, AutoCloseable {
    private final URI registration;
    private final URI authentication;
    private final HttpClient client;
    private final Gson gson = new GsonBuilder().setStrictness(Strictness.STRICT).create();

    public HttpAccountServices(URI registration, URI authentication) {
        this.registration = requireOrigin(registration);
        this.authentication = requireOrigin(authentication);
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    }

    @Override public boolean hasAccount(String playerId) {
        JsonObject body = send(registration.resolve("/accounts/by-player/" + URLEncoder.encode(playerId, StandardCharsets.UTF_8).replace("+", "%20")),
                "GET", null, null, 200);
        var value = body.get("registered");
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean())
            throw new IllegalStateException("Account service returned an invalid registration state.");
        return value.getAsBoolean();
    }

    @Override public String register(String credential, String username, String password) {
        JsonObject body = send(registration.resolve("/accounts"), "POST", new RegistrationRequest(credential, username, password), null, 201);
        requireStatus(body, "Active");
        return requiredString(body, "playerId");
    }

    @Override public String deactivate(String token, String password) {
        JsonObject body = send(registration.resolve("/accounts/current"), "DELETE", new PasswordRequest(password), token, 200);
        requireStatus(body, "Inactive");
        return requiredString(body, "playerId");
    }

    @Override public Grant login(String username, String password) {
        JsonObject body = send(authentication.resolve("/sessions"), "POST", new LoginRequest(username, password), null, 201);
        try {
            return new Grant(requiredString(body, "token"), readIdentity(body));
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Authentication service returned an invalid session grant.", exception);
        }
    }

    @Override public Identity resume(String token) {
        return readIdentity(send(authentication.resolve("/sessions/current"), "GET", null, token, 200));
    }

    @Override public void logout(String token) {
        try {
            send(authentication.resolve("/sessions/current"), "DELETE", null, token, 204);
        } catch (SecurityException ignored) {
            return;
        }
    }

    private Identity readIdentity(JsonObject body) {
        try {
            return new Identity(requiredString(body, "accountId"), requiredString(body, "playerId"), Instant.parse(requiredString(body, "expiresAt")));
        } catch (IllegalArgumentException | java.time.DateTimeException exception) {
            throw new IllegalStateException("Authentication service returned an invalid identity.", exception);
        }
    }

    private JsonObject send(URI uri, String method, Object payload, String token, int expectedStatus) {
        var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(4)).header("Content-Type", "application/json")
                .method(method, payload == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(gson.toJson(payload), StandardCharsets.UTF_8));
        if (token != null) request.header("Authorization", "Bearer " + token);
        try {
            var response = client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
            try (var input = response.body()) {
                if (response.statusCode() == 401) throw new SecurityException("Account credentials are invalid or expired.");
                if (response.statusCode() == 409) throw new AccountConflictException();
                if (response.statusCode() == 400) throw new IllegalArgumentException("Check the username and password requirements.");
                if (response.statusCode() != expectedStatus) throw new IOException("Account service returned an unsuccessful response.");
                if (expectedStatus == 204) return null;
                byte[] body = input.readNBytes(8193);
                if (body.length > 8192) throw new IOException("Account service response is too large.");
                JsonObject fields = gson.fromJson(new String(body, StandardCharsets.UTF_8), JsonObject.class);
                if (fields == null) throw new IOException("Missing account service response.");
                return fields;
            }
        } catch (IOException | JsonParseException exception) {
            throw new IllegalStateException("Account service is unavailable.", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Account service request was interrupted.", exception);
        }
    }

    private String requiredString(JsonObject body, String name) {
        var value = body.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString() || value.getAsString().isBlank())
            throw new IllegalStateException("Account service response is incomplete.");
        return value.getAsString();
    }

    private void requireStatus(JsonObject body, String expected) {
        if (!expected.equals(requiredString(body, "status"))) throw new IllegalStateException("Account service returned an unexpected status.");
    }

    private URI requireOrigin(URI origin) {
        if (origin == null || !Set.of("http", "https").contains(origin.getScheme()) || origin.getHost() == null
                || origin.getRawUserInfo() != null || origin.getRawQuery() != null || origin.getRawFragment() != null
                || !Set.of("", "/").contains(origin.getPath()))
            throw new IllegalArgumentException("Account service must be an HTTP origin.");
        return origin;
    }

    @Override public void close() { client.close(); }

    private record LoginRequest(String username, String password) {}
    private record RegistrationRequest(String credential, String username, String password) {}
    private record PasswordRequest(String password) {}
}
