package software.ulpgc.chess.gameservice;

import io.javalin.Javalin;
import org.junit.jupiter.api.Test;
import software.ulpgc.chess.gameservice.infrastructure.adapters.HttpPlayerIdentity;

import java.net.URI;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class HttpPlayerIdentityTest {
    @Test void rejectsUnsuccessfulMalformedIncompleteAndOversizedServiceResponses() {
        AtomicInteger status = new AtomicInteger(200);
        AtomicReference<String> body = new AtomicReference<>();
        Javalin service = Javalin.create(config -> config.routes.post("/players/resolve", context ->
                context.status(status.get()).contentType("application/json").result(body.get())))
                .start("127.0.0.1", 0);
        try (var identity = new HttpPlayerIdentity(URI.create("http://127.0.0.1:" + service.port()))) {
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
