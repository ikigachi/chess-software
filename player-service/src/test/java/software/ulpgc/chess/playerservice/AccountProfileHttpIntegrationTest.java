package software.ulpgc.chess.playerservice;

import com.google.gson.JsonParser;
import io.javalin.Javalin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.ulpgc.chess.application.domain.model.User;
import software.ulpgc.chess.playerservice.application.usecases.AccountProfileUseCases;
import software.ulpgc.chess.playerservice.application.usecases.RenamePlayerUseCase;
import software.ulpgc.chess.playerservice.application.usecases.ResolvePlayerUseCase;
import software.ulpgc.chess.playerservice.infrastructure.adapters.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class AccountProfileHttpIntegrationTest {
    private static final String TOKEN = "a".repeat(43);
    @TempDir Path directory;
    private final HttpClient client = HttpClient.newHttpClient();
    private Javalin authentication;
    private HttpAccountIdentity accounts;
    private PlayerHttpServer players;
    private SqlitePlayerRepository repository;
    private int authenticationStatus = 200;
    private String authenticationResponse = "{\"playerId\":\"account-player\"}";

    @BeforeEach void start() {
        authentication = Javalin.create(config -> config.routes.get("/sessions/current", context -> {
            if (!("Bearer " + TOKEN).equals(context.header("Authorization"))) {
                context.status(401);
                return;
            }
            context.status(authenticationStatus).contentType("application/json").result(authenticationResponse);
        })).start("127.0.0.1", 0);
        accounts = new HttpAccountIdentity(URI.create("http://127.0.0.1:" + authentication.port()));
        var database = new SqlitePlayerDatabase(directory.resolve("players.sqlite"));
        database.initializeSchema();
        repository = new SqlitePlayerRepository(database);
        startPlayerServer();
    }

    private void startPlayerServer() {
        var resolve = new ResolvePlayerUseCase(repository);
        players = new PlayerHttpServer(0, new PlayerApiHandler(resolve, new RenamePlayerUseCase(resolve, repository),
                new AccountProfileUseCases(repository, accounts)));
        players.start();
    }

    @AfterEach void stop() {
        if (players != null) players.close();
        if (accounts != null) accounts.close();
        if (authentication != null) authentication.stop();
        client.close();
    }

    private HttpResponse<String> requestProfile(String token, String body) throws Exception {
        var request = HttpRequest.newBuilder(players.uri().resolve("/players/current"));
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (body == null) request.GET();
        else request.header("Content-Type", "application/json").PUT(HttpRequest.BodyPublishers.ofString(body));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test void readsAnUnsavedProfileWithoutCreatingIt() throws Exception {
        var response = requestProfile(TOKEN, null);

        assertEquals(200, response.statusCode());
        var profile = JsonParser.parseString(response.body()).getAsJsonObject();
        assertEquals("account-player", profile.get("id").getAsString());
        assertEquals("Guest", profile.get("name").getAsString());
        assertTrue(repository.findById("account-player").isEmpty());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        assertFalse(response.body().contains(TOKEN));
    }

    @Test void updatesOnlyTheAuthenticatedPlayerAndRecoversTheNameAfterRestart() throws Exception {
        var historicalParticipant = new User("account-player", "Original");
        repository.save(historicalParticipant);
        repository.save(new User("another-player", "Other"));

        var response = requestProfile(TOKEN,
                "{\"id\":\"another-player\",\"credential\":\"forged\",\"name\":\" Renamed \"}");

        assertEquals(200, response.statusCode());
        assertEquals(new User("account-player", "Renamed"), repository.findById("account-player").orElseThrow());
        assertEquals("Other", repository.findById("another-player").orElseThrow().name());
        assertEquals("Original", historicalParticipant.name());
        players.close();
        startPlayerServer();
        var restored = requestProfile(TOKEN, null);
        assertEquals(200, restored.statusCode());
        assertEquals(JsonParser.parseString(response.body()), JsonParser.parseString(restored.body()));
    }

    @Test void rejectsMissingMalformedAndExpiredSessionsWithoutChangingTheProfile() throws Exception {
        repository.save(new User("account-player", "Original"));
        for (String token : new String[] {null, "invalid", "b".repeat(43)}) {
            assertEquals(401, requestProfile(token, null).statusCode());
            assertEquals(401, requestProfile(token, "{\"name\":\"Rejected\"}").statusCode());
        }
        authenticationStatus = 401;
        assertEquals(401, requestProfile(TOKEN, "{\"name\":\"Rejected\"}").statusCode());
        assertEquals("Original", repository.findById("account-player").orElseThrow().name());
    }

    @Test void rejectsInvalidNamesAndJsonWithoutChangingTheProfile() throws Exception {
        repository.save(new User("account-player", "Original"));
        for (String body : new String[] {"{\"name\":\" \"}", "{\"name\":12}", "{", "{}"})
            assertEquals(400, requestProfile(TOKEN, body).statusCode());
        assertEquals("Original", repository.findById("account-player").orElseThrow().name());
    }

    @Test void refusesUnavailableOrInvalidAuthenticationResponsesWithoutChangingTheProfile() throws Exception {
        repository.save(new User("account-player", "Original"));
        authenticationStatus = 503;
        assertEquals(503, requestProfile(TOKEN, "{\"name\":\"Rejected\"}").statusCode());
        authenticationStatus = 200;
        for (String response : new String[] {"{}", "{\"playerId\":12}", "{", "x".repeat(8193)}) {
            authenticationResponse = response;
            assertEquals(503, requestProfile(TOKEN, "{\"name\":\"Rejected\"}").statusCode());
        }
        authentication.stop();
        assertEquals(503, requestProfile(TOKEN, "{\"name\":\"Rejected\"}").statusCode());
        assertEquals("Original", repository.findById("account-player").orElseThrow().name());
    }
}
