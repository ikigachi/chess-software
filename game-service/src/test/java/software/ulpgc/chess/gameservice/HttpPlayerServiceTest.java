package software.ulpgc.chess.gameservice;

import io.javalin.Javalin;
import org.junit.jupiter.api.Test;
import software.ulpgc.chess.application.domain.model.User;
import software.ulpgc.chess.gameservice.infrastructure.adapters.HttpPlayerService;

import java.net.URI;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class HttpPlayerServiceTest {
    @Test void accountProfilesUseBearerAuthorizationAndSendOnlyTheRequestedName() {
        String token = "a".repeat(43);
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        var service = Javalin.create(config -> {
            config.routes.get("/players/current", context -> {
                authorization.set(context.header("Authorization"));
                requestBody.set(context.body());
                context.contentType("application/json").result("{\"id\":\"player\",\"name\":\"Alice\"}");
            });
            config.routes.put("/players/current", context -> {
                authorization.set(context.header("Authorization"));
                requestBody.set(context.body());
                context.contentType("application/json").result("{\"id\":\"player\",\"name\":\"Renamed\"}");
            });
        }).start("127.0.0.1", 0);
        try (var players = new HttpPlayerService(URI.create("http://127.0.0.1:" + service.port()))) {
            assertEquals(new User("player", "Alice"), players.loadProfile(token));
            assertEquals("Bearer " + token, authorization.get());
            assertEquals("", requestBody.get());
            assertEquals(new User("player", "Renamed"), players.updateProfile(token, "Renamed"));
            assertEquals("Bearer " + token, authorization.get());
            assertEquals("{\"name\":\"Renamed\"}", requestBody.get());
        } finally { service.stop(); }
    }

    @Test void rejectedAndMalformedAccountProfilesNeverReturnAPlayer() {
        AtomicInteger status = new AtomicInteger(401);
        AtomicReference<String> body = new AtomicReference<>("{}");
        var service = Javalin.create(config -> {
            config.routes.get("/players/current", context ->
                    context.status(status.get()).contentType("application/json").result(body.get()));
            config.routes.put("/players/current", context ->
                    context.status(status.get()).contentType("application/json").result(body.get()));
        }).start("127.0.0.1", 0);
        try (var players = new HttpPlayerService(URI.create("http://127.0.0.1:" + service.port()))) {
            assertThrows(SecurityException.class, () -> players.loadProfile("expired"));
            assertThrows(SecurityException.class, () -> players.updateProfile("expired", "Renamed"));
            status.set(200);
            for (String invalid : List.of("null", "{}", "{\"id\":\"\",\"name\":\"Alice\"}", "x".repeat(8193))) {
                body.set(invalid);
                assertThrows(IllegalStateException.class, () -> players.loadProfile("token"));
                assertThrows(IllegalStateException.class, () -> players.updateProfile("token", "Renamed"));
            }
            service.stop();
            assertThrows(IllegalStateException.class, () -> players.loadProfile("token"));
            assertThrows(IllegalStateException.class, () -> players.updateProfile("token", "Renamed"));
        } finally { service.stop(); }
    }

    @Test void rejectsUnsuccessfulMalformedIncompleteAndOversizedServiceResponses() {
        AtomicInteger status = new AtomicInteger(200);
        AtomicReference<String> body = new AtomicReference<>();
        Javalin service = Javalin.create(config -> config.routes.post("/players/resolve", context ->
                context.status(status.get()).contentType("application/json").result(body.get())))
                .start("127.0.0.1", 0);
        try (var identity = new HttpPlayerService(URI.create("http://127.0.0.1:" + service.port()))) {
            for (String invalid : List.of("{", "[]", "null", "{}", "{\"id\":\"player\",\"name\":\"\"}",
                    "{\"id\":42,\"name\":true}", "x".repeat(8193))) {
                body.set(invalid);
                var failure = assertThrows(IllegalStateException.class, () -> identity.resolve("123e4567-e89b-12d3-a456-426614174000"));
                assertEquals("Player service is unavailable.", failure.getMessage());
                assertNotNull(failure.getCause());
            }
            status.set(503);
            body.set("{\"message\":\"Unavailable\"}");
            assertThrows(IllegalStateException.class, () -> identity.resolve("123e4567-e89b-12d3-a456-426614174000"));
        } finally {
            service.stop();
        }
    }
}
