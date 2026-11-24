package software.ulpgc.chess.registerservice;

import com.google.gson.JsonParser;
import io.javalin.Javalin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.ulpgc.chess.registerservice.application.usecases.AccountUseCases;
import software.ulpgc.chess.registerservice.infrastructure.adapters.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AuthenticatedAccountHttpIntegrationTest {
    private static final String ALICE = "123e4567-e89b-12d3-a456-426614174000";
    private static final String BOB = "123e4567-e89b-12d3-a456-426614174001";
    private static final String PASSWORD = "white knight on e4";
    private static final String TOKEN = "a".repeat(43);
    @TempDir Path directory;
    private final HttpClient client = HttpClient.newHttpClient();
    private Javalin authentication;
    private HttpAccountIdentity identity;
    private SqliteAccountDatabase database;
    private SqliteAccountRepository repository;
    private RegisterHttpServer registration;
    private int authenticationStatus = 200;
    private String authenticationResponse = "{\"playerId\":\"alice-player\"}";

    @BeforeEach void start() {
        authentication = Javalin.create(config -> config.routes.get("/sessions/current", context -> {
            if (!("Bearer " + TOKEN).equals(context.header("Authorization"))) {
                context.status(401);
                return;
            }
            context.status(authenticationStatus).contentType("application/json").result(authenticationResponse);
        })).start("127.0.0.1", 0);
        identity = new HttpAccountIdentity(URI.create("http://127.0.0.1:" + authentication.port()));
        database = new SqliteAccountDatabase(directory.resolve("accounts.sqlite"));
        database.initializeSchema();
        repository = new SqliteAccountRepository(database);
        var useCases = createAccountUseCases();
        useCases.registerAccount(ALICE, "alice", PASSWORD);
        useCases.registerAccount(BOB, "bob", PASSWORD);
        startRegistration();
    }

    private AccountUseCases createAccountUseCases() {
        return new AccountUseCases(repository, credential -> {
            if (ALICE.equals(credential)) return "alice-player";
            if (BOB.equals(credential)) return "bob-player";
            throw new IllegalArgumentException("Unknown guest credential.");
        }, new Argon2PasswordHasher(), identity);
    }

    private void startRegistration() {
        registration = new RegisterHttpServer(0, new AccountApiHandler(createAccountUseCases()));
        registration.start();
    }

    @AfterEach void stop() {
        if (registration != null) registration.close();
        if (identity != null) identity.close();
        if (authentication != null) authentication.stop();
        client.close();
    }

    private HttpResponse<String> lookup(String playerId) throws Exception {
        return client.send(HttpRequest.newBuilder(registration.uri().resolve("/accounts/by-player/" + playerId))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> deactivate(String authorization, String body) throws Exception {
        var request = HttpRequest.newBuilder(registration.uri().resolve("/accounts/current"))
                .header("Content-Type", "application/json")
                .method("DELETE", HttpRequest.BodyPublishers.ofString(body));
        if (authorization != null) request.header("Authorization", authorization);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> deactivateAlice() throws Exception {
        return deactivate("Bearer " + TOKEN, "{\"password\":\"" + PASSWORD + "\"}");
    }

    @Test void lookupPreservesTheAssociationAfterDeactivationAndRestartWithoutExposingAccountDetails() throws Exception {
        var response = lookup("alice-player");
        assertEquals(200, response.statusCode());
        assertEquals("{\"registered\":true}", response.body());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        assertTrue(response.headers().firstValue("Set-Cookie").isEmpty());
        assertEquals("{\"registered\":false}", lookup("unknown-player").body());
        assertEquals(200, deactivateAlice().statusCode());
        registration.close();
        startRegistration();
        assertEquals("{\"registered\":true}", lookup("alice-player").body());
        assertFalse(repository.findByPlayerId("alice-player").orElseThrow().isActive());
        var unsupported = client.send(HttpRequest.newBuilder(registration.uri().resolve("/accounts/by-player/alice-player"))
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(405, unsupported.statusCode());
        assertEquals("GET", unsupported.headers().firstValue("Allow").orElseThrow());
    }

    @Test void deactivationUsesTheVerifiedActorAndPreservesCredentialsAndOtherAccounts() throws Exception {
        var alice = repository.findByPlayerId("alice-player").orElseThrow();
        var bob = repository.findByPlayerId("bob-player").orElseThrow();
        String passwordHash = repository.findPasswordHash(alice.id()).orElseThrow();

        var response = deactivate("Bearer " + TOKEN, "{\"password\":\"" + PASSWORD
                + "\",\"credential\":\"" + BOB + "\",\"playerId\":\"bob-player\",\"id\":\"" + bob.id() + "\"}");

        assertEquals(200, response.statusCode());
        var account = JsonParser.parseString(response.body()).getAsJsonObject();
        assertEquals(alice.id(), account.get("id").getAsString());
        assertEquals("alice-player", account.get("playerId").getAsString());
        assertEquals("Inactive", account.get("status").getAsString());
        assertEquals(bob, repository.findByPlayerId("bob-player").orElseThrow());
        assertEquals(passwordHash, repository.findPasswordHash(alice.id()).orElseThrow());
        assertFalse(response.body().contains(TOKEN));
        assertFalse(response.body().contains(PASSWORD));
        assertEquals(401, deactivateAlice().statusCode());
    }

    @Test void anInvalidBearerCannotFallBackToTheSuppliedGuestCredential() throws Exception {
        String body = "{\"credential\":\"" + ALICE + "\",\"password\":\"" + PASSWORD + "\"}";
        for (String authorization : List.of("Basic invalid", "Bearer invalid", "Bearer " + "b".repeat(43)))
            assertEquals(401, deactivate(authorization, body).statusCode());
        authenticationStatus = 401;
        assertEquals(401, deactivate("Bearer " + TOKEN, body).statusCode());
        assertTrue(repository.findByPlayerId("alice-player").orElseThrow().isActive());
    }

    @Test void wrongOrMissingPasswordsLeaveTheAuthenticatedAccountActive() throws Exception {
        for (String body : List.of("{\"password\":\"a wrong but long password\"}", "{}", "{\"password\":12}")) {
            int expectedStatus = body.contains("wrong") ? 401 : 400;
            assertEquals(expectedStatus, deactivate("Bearer " + TOKEN, body).statusCode());
        }
        assertEquals(400, deactivate(null, "{\"password\":\"" + PASSWORD + "\"}").statusCode());
        assertTrue(repository.findByPlayerId("alice-player").orElseThrow().isActive());
    }

    @Test void authenticationFailuresNeverDeactivateAnAccount() throws Exception {
        authenticationStatus = 503;
        assertEquals(503, deactivateAlice().statusCode());
        authenticationStatus = 200;
        for (String response : List.of("{}", "{\"playerId\":12}", "{", "x".repeat(8193))) {
            authenticationResponse = response;
            assertEquals(503, deactivateAlice().statusCode());
        }
        authentication.stop();
        assertEquals(503, deactivateAlice().statusCode());
        assertTrue(repository.findByPlayerId("alice-player").orElseThrow().isActive());
        assertTrue(repository.findByPlayerId("bob-player").orElseThrow().isActive());
    }

    @Test void storageFailuresPreserveTheActiveAccountAndNeverReportAnAbsentAssociation() throws Exception {
        var original = repository.findByPlayerId("alice-player").orElseThrow();
        try (var connection = database.openConnection(); var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TRIGGER reject_deactivation BEFORE UPDATE ON accounts
                    BEGIN SELECT RAISE(ABORT, 'Test write failure'); END
                    """);
        }
        assertEquals(503, deactivateAlice().statusCode());
        assertEquals(original, repository.findByPlayerId("alice-player").orElseThrow());
        try (var connection = database.openConnection(); var statement = connection.createStatement()) {
            statement.executeUpdate("ALTER TABLE accounts RENAME TO unavailable_accounts");
        }
        assertEquals(503, lookup("alice-player").statusCode());
    }
}
