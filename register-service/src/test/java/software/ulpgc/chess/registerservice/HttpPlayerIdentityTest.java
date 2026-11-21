package software.ulpgc.chess.registerservice;

import io.javalin.Javalin;
import org.junit.jupiter.api.Test;
import software.ulpgc.chess.registerservice.infrastructure.adapters.HttpPlayerIdentity;

import java.net.URI;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class HttpPlayerIdentityTest {
    @Test void rejectsInvalidOriginsAndInvalidOrOversizedIdentityResponses() {
        for (String origin : List.of("ftp://localhost", "http://localhost/path", "http://user@localhost", "http://localhost?query"))
            assertThrows(IllegalArgumentException.class, () -> new HttpPlayerIdentity(URI.create(origin)));

        AtomicReference<String> response = new AtomicReference<>();
        var server = Javalin.create(config -> config.routes.post("/players/resolve", context ->
                context.contentType("application/json").result(response.get()))).start("127.0.0.1", 0);
        try (var identity = new HttpPlayerIdentity(URI.create("http://127.0.0.1:" + server.port()))) {
            String credential = "123e4567-e89b-12d3-a456-426614174000";
            assertThrows(IllegalArgumentException.class, () -> identity.resolvePlayerId("invalid"));
            for (String body : List.of("{}", "{\"id\":12}", "{\"id\":\" \"}", "null", "not-json", " ".repeat(8193))) {
                response.set(body);
                assertThrows(IllegalStateException.class, () -> identity.resolvePlayerId(credential));
            }
        } finally {
            server.stop();
        }
    }
}
