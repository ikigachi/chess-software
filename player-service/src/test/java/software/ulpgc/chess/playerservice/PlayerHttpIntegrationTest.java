package software.ulpgc.chess.playerservice;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.ulpgc.chess.playerservice.application.usecases.ResolvePlayerUseCase;
import software.ulpgc.chess.playerservice.application.usecases.RenamePlayerUseCase;
import software.ulpgc.chess.playerservice.infrastructure.adapters.SqlitePlayerDatabase;
import software.ulpgc.chess.playerservice.infrastructure.adapters.SqlitePlayerRepository;
import software.ulpgc.chess.playerservice.infrastructure.adapters.PlayerApiHandler;
import software.ulpgc.chess.playerservice.infrastructure.adapters.PlayerHttpServer;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PlayerHttpIntegrationTest {
    private PlayerHttpServer server;
    @TempDir Path directory;
    private SqlitePlayerDatabase database;
    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach void start() {
        database = new SqlitePlayerDatabase(directory.resolve("players.sqlite"));
        database.initializeSchema();
        var repository = new SqlitePlayerRepository(database);
        var resolve = new ResolvePlayerUseCase(repository);
        server = new PlayerHttpServer(0, new PlayerApiHandler(resolve, new RenamePlayerUseCase(resolve, repository)));
        server.start();
    }

    @AfterEach void stop() {
        server.close();
        client.close();
    }

    private HttpResponse<String> requestPlayerResolution(String body, String contentType) throws Exception {
        return client.send(HttpRequest.newBuilder(server.uri().resolve("/players/resolve"))
                .header("Content-Type", contentType).POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test void resolvesAnIdentityWithoutReturningItsCredentialAndPreservesItAfterRestart() throws Exception {
        String credential = "123e4567-e89b-12d3-a456-426614174000";
        String body = "{\"credential\":\"" + credential + "\"}";
        var first = requestPlayerResolution(body, "application/json");
        assertEquals(200, first.statusCode());
        var player = JsonParser.parseString(first.body()).getAsJsonObject();
        assertEquals("986c0dc956dc822b5d8f698661b9eb1ef880786ff9043c16744d2a420e99e9bb", player.get("id").getAsString());
        assertEquals("Guest", player.get("name").getAsString());
        assertFalse(first.body().contains(credential));
        assertEquals("no-store", first.headers().firstValue("Cache-Control").orElseThrow());
        assertTrue(first.headers().firstValue("Set-Cookie").isEmpty());

        server.close();
        start();

        var restored = requestPlayerResolution(body, "application/json; charset=UTF-8");
        assertEquals(200, restored.statusCode());
        assertEquals(player, JsonParser.parseString(restored.body()));
    }

    private HttpResponse<String> requestPlayerRename(String body, String contentType) throws Exception {
        return client.send(HttpRequest.newBuilder(server.uri().resolve("/players/name"))
                .header("Content-Type", contentType).PUT(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test void changesThePublicNameAndPreservesItAfterRestart() throws Exception {
        String credential = "123e4567-e89b-12d3-a456-426614174000";
        String lookup = "{\"credential\":\"" + credential + "\"}";
        var before = JsonParser.parseString(requestPlayerResolution(lookup, "application/json").body()).getAsJsonObject();

        var response = requestPlayerRename("{\"credential\":\"" + credential + "\",\"name\":\" Zoë \"}", "application/json");

        assertEquals(200, response.statusCode());
        var renamed = JsonParser.parseString(response.body()).getAsJsonObject();
        assertEquals(before.get("id"), renamed.get("id"));
        assertEquals("Zoë", renamed.get("name").getAsString());
        assertEquals(renamed, JsonParser.parseString(requestPlayerResolution(lookup, "application/json").body()));
        server.close();
        start();
        assertEquals(renamed, JsonParser.parseString(requestPlayerResolution(lookup, "application/json").body()));
    }

    @Test void unavailableStorageReturns503AndKeepsTheSavedName() throws Exception {
        String credential = "123e4567-e89b-12d3-a456-426614174000";
        String lookup = "{\"credential\":\"" + credential + "\"}";
        String original = "{\"credential\":\"" + credential + "\",\"name\":\"Alice\"}";
        assertEquals(200, requestPlayerRename(original, "application/json").statusCode());
        try (var connection = database.openConnection(); var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TRIGGER reject_name_changes BEFORE UPDATE ON players
                    BEGIN SELECT RAISE(ABORT, 'Test write failure'); END
                    """);
        }

        var failure = requestPlayerRename("{\"credential\":\"" + credential + "\",\"name\":\"Renamed\"}", "application/json");

        assertEquals(503, failure.statusCode());
        assertEquals("Player storage is unavailable. Try again later.",
                JsonParser.parseString(failure.body()).getAsJsonObject().get("message").getAsString());
        assertEquals("Alice", JsonParser.parseString(requestPlayerResolution(lookup, "application/json").body()).getAsJsonObject().get("name").getAsString());
    }

    @Test void invalidRenameRequestsPreserveThePreviousName() throws Exception {
        String credential = "123e4567-e89b-12d3-a456-426614174000";
        String lookup = "{\"credential\":\"" + credential + "\"}";
        String valid = "{\"credential\":\"" + credential + "\",\"name\":\"Alice\"}";
        assertEquals(200, requestPlayerRename(valid, "application/json").statusCode());
        for (String invalid : List.of("{", "[]", "null", "{} {}", lookup,
                "{\"credential\":\"" + credential + "\",\"name\":23}",
                "{\"credential\":\"" + credential + "\",\"name\":\" \"}",
                "{\"credential\":\"" + credential + "\",\"name\":\"" + "x".repeat(41) + "\"}")) {
            assertEquals(400, requestPlayerRename(invalid, "application/json").statusCode(), invalid);
            assertEquals("Alice", JsonParser.parseString(requestPlayerResolution(lookup, "application/json").body()).getAsJsonObject().get("name").getAsString());
        }
        assertEquals(415, requestPlayerRename(valid, "text/plain").statusCode());
        assertEquals(413, requestPlayerRename("x".repeat(8193), "application/json").statusCode());
        var wrongMethod = client.send(HttpRequest.newBuilder(server.uri().resolve("/players/name")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(405, wrongMethod.statusCode());
        assertEquals("PUT", wrongMethod.headers().firstValue("Allow").orElseThrow());
        assertEquals("Alice", JsonParser.parseString(requestPlayerResolution(lookup, "application/json").body()).getAsJsonObject().get("name").getAsString());
    }

    @Test void rejectsInvalidJsonCredentialsContentTypesBodiesRoutesAndMethods() throws Exception {
        for (String invalid : List.of("{", "[]", "null", "{} {}", "{}", "{\"credential\":23}", "{\"credential\":\"wrong\"}")) {
            var response = requestPlayerResolution(invalid, "application/json");
            assertEquals(400, response.statusCode(), invalid);
            assertTrue(JsonParser.parseString(response.body()).getAsJsonObject().has("message"));
        }
        assertEquals(415, requestPlayerResolution("{}", "text/plain").statusCode());
        assertEquals(413, requestPlayerResolution("x".repeat(8193), "application/json").statusCode());
        var wrongMethod = client.send(HttpRequest.newBuilder(server.uri().resolve("/players/resolve")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(405, wrongMethod.statusCode());
        assertEquals("POST", wrongMethod.headers().firstValue("Allow").orElseThrow());
        var missing = client.send(HttpRequest.newBuilder(server.uri().resolve("/unknown")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(404, missing.statusCode());
    }
}
