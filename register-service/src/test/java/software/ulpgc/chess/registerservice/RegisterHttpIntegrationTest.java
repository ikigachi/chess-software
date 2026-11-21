package software.ulpgc.chess.registerservice;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.ulpgc.chess.application.domain.model.User;
import software.ulpgc.chess.playerservice.application.usecases.RenamePlayerUseCase;
import software.ulpgc.chess.playerservice.application.usecases.ResolvePlayerUseCase;
import software.ulpgc.chess.playerservice.infrastructure.adapters.InMemoryPlayerRepository;
import software.ulpgc.chess.playerservice.infrastructure.adapters.PlayerApiHandler;
import software.ulpgc.chess.playerservice.infrastructure.adapters.PlayerHttpServer;
import software.ulpgc.chess.registerservice.application.usecases.AccountUseCases;
import software.ulpgc.chess.registerservice.infrastructure.adapters.*;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

class RegisterHttpIntegrationTest {
    private static final String ALICE = "123e4567-e89b-12d3-a456-426614174000";
    private static final String BOB = "123e4567-e89b-12d3-a456-426614174001";
    private static final String PASSWORD = "white knight on e4";
    @TempDir Path directory;
    private RegisterHttpServer server;
    private PlayerHttpServer playerServer;
    private HttpPlayerIdentity identities;
    private SqliteAccountRepository accounts;
    private InMemoryPlayerRepository profiles;
    private final HttpClient client = HttpClient.newHttpClient();
    private final Gson gson = new Gson();

    @BeforeEach void start() {
        profiles = new InMemoryPlayerRepository();
        var resolve = new ResolvePlayerUseCase(profiles);
        playerServer = new PlayerHttpServer(0, new PlayerApiHandler(resolve, new RenamePlayerUseCase(resolve, profiles)));
        playerServer.start();
        identities = new HttpPlayerIdentity(playerServer.uri());
        startRegistration();
    }

    private void startRegistration() {
        var database = new SqliteAccountDatabase(directory.resolve("accounts.sqlite"));
        database.initializeSchema();
        accounts = new SqliteAccountRepository(database);
        server = new RegisterHttpServer(0, new AccountApiHandler(new AccountUseCases(accounts, identities, new Argon2PasswordHasher())));
        server.start();
    }

    @AfterEach void stop() {
        server.close();
        identities.close();
        playerServer.close();
        client.close();
    }

    @Test void registersAndDeactivatesAfterRestartWithoutChangingThePlayerProfileOrReturningSecrets() throws Exception {
        String playerId = identities.resolvePlayerId(ALICE);
        User historicalName = new User(playerId, "Recorded Alice");
        profiles.save(historicalName);

        var created = register(ALICE, " Alice ");

        assertEquals(201, created.statusCode());
        JsonObject account = json(created);
        assertEquals("alice", account.get("username").getAsString());
        assertEquals(playerId, account.get("playerId").getAsString());
        assertEquals("Active", account.get("status").getAsString());
        assertFalse(created.body().contains(ALICE));
        assertFalse(created.body().contains(PASSWORD));
        assertFalse(created.body().contains("password"));
        assertTrue(created.headers().firstValue("Set-Cookie").isEmpty());
        assertEquals("no-store", created.headers().firstValue("Cache-Control").orElseThrow());
        String hash = accounts.findPasswordHash(account.get("id").getAsString()).orElseThrow();
        assertTrue(hash.startsWith("$argon2id$v=19$m=19456,t=2,p=1$"));
        assertTrue(new Argon2PasswordHasher().matches(PASSWORD, hash));

        server.close();
        startRegistration();
        var deactivated = deactivate(ALICE, PASSWORD);

        assertEquals(200, deactivated.statusCode());
        assertEquals(account.get("id"), json(deactivated).get("id"));
        assertEquals(account.get("playerId"), json(deactivated).get("playerId"));
        assertEquals("Inactive", json(deactivated).get("status").getAsString());
        assertEquals(hash, accounts.findPasswordHash(account.get("id").getAsString()).orElseThrow());
        assertEquals(historicalName, profiles.findById(playerId).orElseThrow());
        assertEquals(401, deactivate(ALICE, PASSWORD).statusCode());
    }

    @Test void duplicateNamesAndPlayerAssociationsReturn409WithoutReplacingCredentials() throws Exception {
        assertEquals(201, register(ALICE, "alice").statusCode());
        var original = accounts.findByUsername("alice").orElseThrow();
        String hash = accounts.findPasswordHash(original.id()).orElseThrow();

        assertEquals(409, register(BOB, " ALICE ").statusCode());
        assertEquals(409, register(ALICE, "another-name").statusCode());
        assertEquals(original, accounts.findByUsername("alice").orElseThrow());
        assertEquals(hash, accounts.findPasswordHash(original.id()).orElseThrow());
        assertTrue(accounts.findByPlayerId(identities.resolvePlayerId(BOB)).isEmpty());
        assertEquals(200, deactivate(ALICE, PASSWORD).statusCode());
        assertEquals(409, register(BOB, "alice").statusCode());
        assertFalse(accounts.findByUsername("alice").orElseThrow().isActive());
    }

    @Test void declaredIdentifiersCannotSelectAnotherPlayerAndDeactivationRequiresTheirPassword() throws Exception {
        String bobId = identities.resolvePlayerId(BOB);
        var created = request("POST", "/accounts", gson.toJson(Map.of(
                "credential", ALICE, "username", "alice", "password", PASSWORD,
                "playerId", bobId, "id", "chosen-account", "status", "Inactive")), "application/json");

        assertEquals(201, created.statusCode());
        var original = accounts.findByUsername("alice").orElseThrow();
        assertEquals(identities.resolvePlayerId(ALICE), original.playerId());
        assertNotEquals("chosen-account", original.id());
        assertTrue(original.isActive());
        assertEquals(401, deactivate(ALICE, "a wrong but long password").statusCode());
        var forged = request("DELETE", "/accounts/current", gson.toJson(Map.of(
                "credential", BOB, "password", PASSWORD, "playerId", original.playerId(), "id", original.id())), "application/json");
        assertEquals(401, forged.statusCode());
        assertEquals(original, accounts.findByUsername("alice").orElseThrow());
    }

    @Test void malformedRequestsAndUnsupportedRoutesReturnJsonWithoutCreatingAccounts() throws Exception {
        for (String body : List.of("{", "null", "[]", "{}", "{\"credential\":12}",
                gson.toJson(Map.of("credential", "not-a-uuid", "username", "alice", "password", PASSWORD)),
                gson.toJson(Map.of("credential", ALICE, "username", "alice", "password", "short")))) {
            var rejected = request("POST", "/accounts", body, "application/json");
            assertEquals(400, rejected.statusCode());
            assertTrue(json(rejected).has("message"));
            assertFalse(rejected.body().contains(ALICE));
        }
        assertEquals(415, request("POST", "/accounts", "{}", "text/plain").statusCode());
        assertEquals(413, request("POST", "/accounts", " ".repeat(8193), "application/json").statusCode());
        var unsupported = request("GET", "/accounts", "", "application/json");
        assertEquals(405, unsupported.statusCode());
        assertEquals("POST", unsupported.headers().firstValue("Allow").orElseThrow());
        assertEquals(404, request("GET", "/missing", "", "application/json").statusCode());
        assertTrue(accounts.findByUsername("alice").isEmpty());
    }

    @Test void anUnavailableIdentityServiceReturns503WithoutCreatingAnAccount() throws Exception {
        playerServer.close();

        var response = register(ALICE, "alice");

        assertEquals(503, response.statusCode());
        assertTrue(json(response).has("message"));
        assertFalse(response.body().contains(ALICE));
        assertTrue(accounts.findByUsername("alice").isEmpty());
    }

    @Test void aCredentialWriteFailureReturns503AndCanBeRetriedWithoutAPartialAccount() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("accounts.sqlite"));
             var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TRIGGER reject_password BEFORE INSERT ON account_passwords
                    BEGIN SELECT RAISE(ABORT, 'Test failure'); END
                    """);
        }

        assertEquals(503, register(ALICE, "alice").statusCode());
        assertTrue(accounts.findByUsername("alice").isEmpty());
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("accounts.sqlite"));
             var statement = connection.createStatement()) {
            statement.executeUpdate("DROP TRIGGER reject_password");
        }
        assertEquals(201, register(ALICE, "alice").statusCode());
    }

    @Test void simultaneousRegistrationsCannotClaimTheSameUsernameTwice() {
        var first = CompletableFuture.supplyAsync(() -> registerUnchecked(ALICE));
        var second = CompletableFuture.supplyAsync(() -> registerUnchecked(BOB));

        var responses = List.of(first.join(), second.join());

        assertEquals(List.of(201, 409), responses.stream().map(HttpResponse::statusCode).sorted().toList());
        var saved = accounts.findByUsername("alice").orElseThrow();
        assertTrue(accounts.findPasswordHash(saved.id()).isPresent());
        String otherId = identities.resolvePlayerId(saved.playerId().equals(identities.resolvePlayerId(ALICE)) ? BOB : ALICE);
        assertTrue(accounts.findByPlayerId(otherId).isEmpty());
    }

    private HttpResponse<String> registerUnchecked(String credential) {
        try {
            return register(credential, "alice");
        } catch (Exception exception) {
            throw new CompletionException(exception);
        }
    }

    private HttpResponse<String> register(String credential, String username) throws Exception {
        return request("POST", "/accounts", gson.toJson(Map.of("credential", credential, "username", username, "password", PASSWORD)),
                "application/json");
    }

    private HttpResponse<String> deactivate(String credential, String password) throws Exception {
        return request("DELETE", "/accounts/current", gson.toJson(Map.of("credential", credential, "password", password)),
                "application/json");
    }

    private HttpResponse<String> request(String method, String path, String body, String contentType) throws Exception {
        return client.send(HttpRequest.newBuilder(server.uri().resolve(path)).header("Content-Type", contentType)
                .method(method, HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonObject json(HttpResponse<String> response) {
        assertTrue(response.headers().firstValue("Content-Type").orElseThrow().startsWith("application/json"));
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }
}
