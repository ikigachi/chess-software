package software.ulpgc.chess.playerservice;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.ulpgc.chess.playerservice.application.usecases.ResolvePlayerUseCase;
import software.ulpgc.chess.playerservice.application.usecases.RenamePlayerUseCase;
import software.ulpgc.chess.playerservice.infrastructure.adapters.InMemoryPlayerRepository;
import software.ulpgc.chess.playerservice.infrastructure.adapters.PlayerApiHandler;
import software.ulpgc.chess.playerservice.infrastructure.adapters.PlayerHttpServer;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PlayerHttpIntegrationTest {
    private PlayerHttpServer server;
    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach void start() {
        var repository = new InMemoryPlayerRepository();
        var resolve = new ResolvePlayerUseCase(repository);
        server = new PlayerHttpServer(0, new PlayerApiHandler(resolve, new RenamePlayerUseCase(resolve, repository)));
        server.start();
    }

    @AfterEach void stop() {
        server.close();
        client.close();
    }

    private HttpResponse<String> resolve(String body, String contentType) throws Exception {
        return client.send(HttpRequest.newBuilder(server.uri().resolve("/players/resolve"))
                .header("Content-Type", contentType).POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test void resolvesAnIdentityWithoutReturningItsCredentialAndPreservesItAfterRestart() throws Exception {
        String credential = "123e4567-e89b-12d3-a456-426614174000";
        String body = "{\"credential\":\"" + credential + "\"}";
        var first = resolve(body, "application/json");
        assertEquals(200, first.statusCode());
        var player = JsonParser.parseString(first.body()).getAsJsonObject();
        assertEquals("986c0dc956dc822b5d8f698661b9eb1ef880786ff9043c16744d2a420e99e9bb", player.get("id").getAsString());
        assertEquals("Guest", player.get("name").getAsString());
        assertFalse(first.body().contains(credential));
        assertEquals("no-store", first.headers().firstValue("Cache-Control").orElseThrow());
        assertTrue(first.headers().firstValue("Set-Cookie").isEmpty());

        server.close();
        start();

        var restored = resolve(body, "application/json; charset=UTF-8");
        assertEquals(200, restored.statusCode());
        assertEquals(player, JsonParser.parseString(restored.body()));
    }

    private HttpResponse<String> rename(String body, String contentType) throws Exception {
        return client.send(HttpRequest.newBuilder(server.uri().resolve("/players/name"))
                .header("Content-Type", contentType).PUT(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test void changesThePublicNameAndReturnsItOnLaterResolutions() throws Exception {
        String credential = "123e4567-e89b-12d3-a456-426614174000";
        String lookup = "{\"credential\":\"" + credential + "\"}";
        var before = JsonParser.parseString(resolve(lookup, "application/json").body()).getAsJsonObject();

        var response = rename("{\"credential\":\"" + credential + "\",\"name\":\" Zoë \"}", "application/json");

        assertEquals(200, response.statusCode());
        var renamed = JsonParser.parseString(response.body()).getAsJsonObject();
        assertEquals(before.get("id"), renamed.get("id"));
        assertEquals("Zoë", renamed.get("name").getAsString());
        assertEquals(renamed, JsonParser.parseString(resolve(lookup, "application/json").body()));
    }

    @Test void invalidRenameRequestsPreserveThePreviousName() throws Exception {
        String credential = "123e4567-e89b-12d3-a456-426614174000";
        String lookup = "{\"credential\":\"" + credential + "\"}";
        String valid = "{\"credential\":\"" + credential + "\",\"name\":\"Alice\"}";
        assertEquals(200, rename(valid, "application/json").statusCode());
        for (String invalid : List.of("{", "[]", "null", "{} {}", lookup,
                "{\"credential\":\"" + credential + "\",\"name\":23}",
                "{\"credential\":\"" + credential + "\",\"name\":\" \"}",
                "{\"credential\":\"" + credential + "\",\"name\":\"" + "x".repeat(41) + "\"}")) {
            assertEquals(400, rename(invalid, "application/json").statusCode(), invalid);
            assertEquals("Alice", JsonParser.parseString(resolve(lookup, "application/json").body()).getAsJsonObject().get("name").getAsString());
        }
        assertEquals(415, rename(valid, "text/plain").statusCode());
        assertEquals(413, rename("x".repeat(8193), "application/json").statusCode());
        var wrongMethod = client.send(HttpRequest.newBuilder(server.uri().resolve("/players/name")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(405, wrongMethod.statusCode());
        assertEquals("PUT", wrongMethod.headers().firstValue("Allow").orElseThrow());
        assertEquals("Alice", JsonParser.parseString(resolve(lookup, "application/json").body()).getAsJsonObject().get("name").getAsString());
    }

    @Test void rejectsInvalidJsonCredentialsContentTypesBodiesRoutesAndMethods() throws Exception {
        for (String invalid : List.of("{", "[]", "null", "{} {}", "{}", "{\"credential\":23}", "{\"credential\":\"wrong\"}")) {
            var response = resolve(invalid, "application/json");
            assertEquals(400, response.statusCode(), invalid);
            assertTrue(JsonParser.parseString(response.body()).getAsJsonObject().has("message"));
        }
        assertEquals(415, resolve("{}", "text/plain").statusCode());
        assertEquals(413, resolve("x".repeat(8193), "application/json").statusCode());
        var wrongMethod = client.send(HttpRequest.newBuilder(server.uri().resolve("/players/resolve")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(405, wrongMethod.statusCode());
        assertEquals("POST", wrongMethod.headers().firstValue("Allow").orElseThrow());
        var missing = client.send(HttpRequest.newBuilder(server.uri().resolve("/unknown")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(404, missing.statusCode());
    }
}
