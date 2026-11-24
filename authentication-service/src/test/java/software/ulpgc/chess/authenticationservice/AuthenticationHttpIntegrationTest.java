package software.ulpgc.chess.authenticationservice;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.ulpgc.chess.authenticationservice.application.usecases.AuthenticationUseCases;
import software.ulpgc.chess.authenticationservice.infrastructure.adapters.*;
import software.ulpgc.chess.registerservice.application.domain.model.Account;
import software.ulpgc.chess.registerservice.application.usecases.AccountUseCases;
import software.ulpgc.chess.registerservice.infrastructure.adapters.*;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class AuthenticationHttpIntegrationTest {
    private static final String PASSWORD = "white knight on e4";
    private static final Instant START = Instant.parse("2026-11-21T12:00:00Z");
    @TempDir Path directory;
    private AuthenticationHttpServer server;
    private RegisterHttpServer registration;
    private SqliteAuthenticationDatabase database;
    private SqliteAccountRepository accounts;
    private Account alice;
    private final HttpClient client = HttpClient.newHttpClient();
    private final Gson gson = new Gson();
    private final AtomicLong time = new AtomicLong(START.toEpochMilli());
    private final InstantSource instants = () -> Instant.ofEpochMilli(time.get());
    private final Clock clock = instants.withZone(ZoneOffset.UTC);

    @BeforeEach void start() {
        var accountDatabase = new SqliteAccountDatabase(directory.resolve("accounts.sqlite"));
        accountDatabase.initializeSchema();
        accounts = new SqliteAccountRepository(accountDatabase);
        var accountUseCases = new AccountUseCases(accounts, credential -> "stable-player-" + credential, new Argon2PasswordHasher());
        alice = accountUseCases.registerAccount("alice-guest", "alice", PASSWORD);
        accountUseCases.registerAccount("bob-guest", "bob", PASSWORD);
        registration = new RegisterHttpServer(0, new AccountApiHandler(accountUseCases));
        registration.start();
        database = new SqliteAuthenticationDatabase(directory.resolve("accounts.sqlite"));
        database.initializeSchema();
        startAuthentication();
    }

    private void startAuthentication() {
        var useCases = new AuthenticationUseCases(new SqliteAccountCredentialsReader(database), new Argon2PasswordVerifier(),
                new SqliteSessionRepository(database), new SecureSessionTokens(), clock, Duration.ofHours(8), Duration.ofMinutes(30));
        server = new AuthenticationHttpServer(0, new AuthenticationApiHandler(useCases));
        server.start();
    }

    @AfterEach void stop() {
        server.close();
        registration.close();
        client.close();
    }

    @Test void independentClientsReceiveNewTokensForTheSameStablePlayerAndSessionsSurviveRestart() throws Exception {
        String chosenToken = new SecureSessionTokens().generate();
        var login = request(client, "POST", "/sessions", gson.toJson(Map.of("username", " ALICE ", "password", PASSWORD,
                "token", chosenToken, "playerId", "someone-else", "expiresAt", "2099-01-01T00:00:00Z")), null, "application/json");
        assertEquals(201, login.statusCode());
        JsonObject first = json(login);
        String token = first.get("token").getAsString();
        assertNotEquals(chosenToken, token);
        assertEquals(alice.id(), first.get("accountId").getAsString());
        assertEquals(alice.playerId(), first.get("playerId").getAsString());
        assertEquals(START.plusSeconds(28800).toString(), first.get("expiresAt").getAsString());
        assertFalse(login.body().contains(PASSWORD));
        assertFalse(login.body().contains("passwordHash"));
        assertTrue(login.headers().firstValue("Set-Cookie").isEmpty());
        assertEquals("no-store", login.headers().firstValue("Cache-Control").orElseThrow());
        try (var anotherBrowser = HttpClient.newHttpClient()) {
            var second = request(anotherBrowser, "POST", "/sessions", loginBody("alice", PASSWORD), null, "application/json");
            assertEquals(201, second.statusCode());
            assertNotEquals(token, json(second).get("token").getAsString());
            assertEquals(first.get("playerId"), json(second).get("playerId"));
        }
        try (var connection = database.openConnection();
             var query = connection.prepareStatement("SELECT token_hash FROM authentication_sessions");
             var rows = query.executeQuery()) {
            int count = 0;
            while (rows.next()) {
                String stored = rows.getString(1);
                assertTrue(stored.matches("[a-f0-9]{64}"));
                assertNotEquals(token, stored);
                count++;
            }
            assertEquals(2, count);
        }

        server.close();
        database.initializeSchema();
        startAuthentication();
        var restored = session("GET", token);
        assertEquals(200, restored.statusCode());
        assertEquals(first.get("playerId"), json(restored).get("playerId"));
        assertFalse(restored.body().contains(token));
    }

    @Test void logoutRevokesOnlyTheSelectedSessionAndCanBeRepeated() throws Exception {
        String first = loginToken("alice");
        String second = loginToken("alice");
        String bob = loginToken("bob");

        var logout = session("DELETE", first);

        assertEquals(204, logout.statusCode());
        assertTrue(logout.body().isEmpty());
        assertEquals(401, session("GET", first).statusCode());
        assertEquals(200, session("GET", second).statusCode());
        assertEquals(200, session("GET", bob).statusCode());
        assertEquals(204, session("DELETE", first).statusCode());
        assertEquals(alice, accounts.findByUsername("alice").orElseThrow());
    }

    @Test void deactivationThroughTheRegistrationServerInvalidatesEverySessionAndRejectsNewLogins() throws Exception {
        String first = loginToken("alice");
        String second = loginToken("alice");
        String bob = loginToken("bob");
        var deactivate = client.send(HttpRequest.newBuilder(registration.uri().resolve("/accounts/current"))
                .header("Content-Type", "application/json")
                .method("DELETE", HttpRequest.BodyPublishers.ofString(gson.toJson(Map.of("credential", "alice-guest", "password", PASSWORD))))
                .build(), HttpResponse.BodyHandlers.ofString());

        assertEquals(200, deactivate.statusCode());
        assertEquals(401, session("GET", first).statusCode());
        assertEquals(401, session("GET", second).statusCode());
        assertEquals(401, login("alice", PASSWORD).statusCode());
        assertEquals(200, session("GET", bob).statusCode());
        server.close();
        startAuthentication();
        assertEquals(401, session("GET", first).statusCode());
        assertEquals(alice.deactivate(), accounts.findByUsername("alice").orElseThrow());
    }

    @Test void inactivityExpiresAtThirtyMinutesAndActivityCannotExtendTheEightHourLimit() throws Exception {
        String idle = loginToken("alice");
        time.addAndGet(Duration.ofMinutes(30).toMillis());
        assertEquals(401, session("GET", idle).statusCode());
        String active = loginToken("alice");
        long loginTime = time.get();
        String expiry = Instant.ofEpochMilli(loginTime).plus(Duration.ofHours(8)).toString();
        for (int interval = 1; interval <= 16; interval++) {
            time.set(loginTime + Duration.ofMinutes(29L * interval).toMillis());
            var response = session("GET", active);
            assertEquals(200, response.statusCode());
            assertEquals(expiry, json(response).get("expiresAt").getAsString());
        }
        time.set(loginTime + Duration.ofHours(8).toMillis());
        assertEquals(401, session("GET", active).statusCode());
    }

    @Test void unknownInactiveLegacyAndWrongPasswordAccountsReceiveTheSameLoginError() throws Exception {
        accounts.deactivate(accounts.findByUsername("bob").orElseThrow().id());
        try (var connection = database.openConnection(); var statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO accounts VALUES ('legacy-id', 'legacy', 'legacy-player', 'Active')");
        }
        JsonObject error = null;
        for (String username : List.of("alice", "unknown", "bob", "legacy", "' OR 1 = 1", "Kelvin")) {
            var response = login(username, "the wrong chess password");
            assertEquals(401, response.statusCode());
            assertFalse(response.body().contains(username));
            if (error == null) error = json(response);
            else assertEquals(error, json(response));
        }
        assertEquals(0, sessionCount());
    }

    @Test void storageFailuresNeverIssueATokenOrPretendThatLogoutSucceeded() throws Exception {
        String existing = loginToken("alice");
        try (var connection = database.openConnection(); var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TRIGGER reject_login BEFORE INSERT ON authentication_sessions
                    BEGIN SELECT RAISE(ABORT, 'Test login failure'); END;
                    CREATE TRIGGER reject_logout BEFORE DELETE ON authentication_sessions
                    BEGIN SELECT RAISE(ABORT, 'Test logout failure'); END;
                    """);
        }
        var failedLogin = login("bob", PASSWORD);
        assertEquals(503, failedLogin.statusCode());
        assertFalse(json(failedLogin).has("token"));
        assertEquals(1, sessionCount());
        assertEquals(503, session("DELETE", existing).statusCode());
        assertEquals(200, session("GET", existing).statusCode());
        try (var connection = database.openConnection(); var statement = connection.createStatement()) {
            statement.executeUpdate("DROP TRIGGER reject_login; DROP TRIGGER reject_logout");
        }
        assertEquals(204, session("DELETE", existing).statusCode());
        assertEquals(0, sessionCount());
    }

    @Test void malformedRequestsAndAlternativeTokenLocationsDoNotAuthenticate() throws Exception {
        for (String body : List.of("{", "[]", "null", "{\"username\":12}", "{\"username\":\"alice\",\"password\":true}"))
            assertEquals(400, request(client, "POST", "/sessions", body, null, "application/json").statusCode());
        assertEquals(415, request(client, "POST", "/sessions", "{}", null, "text/plain").statusCode());
        assertEquals(413, request(client, "POST", "/sessions", " ".repeat(8193), null, "application/json").statusCode());
        var wrongMethod = request(client, "GET", "/sessions", "", null, "application/json");
        assertEquals(405, wrongMethod.statusCode());
        assertEquals("POST", wrongMethod.headers().firstValue("Allow").orElseThrow());
        assertEquals(404, request(client, "GET", "/missing", "", null, "application/json").statusCode());
        String valid = loginToken("alice");
        assertEquals(401, request(client, "GET", "/sessions/current?token=" + valid, "", null, "application/json").statusCode());
        assertEquals(401, request(client, "GET", "/sessions/current", "", "Basic " + valid, "application/json").statusCode());
        for (String invalid : List.of("invalid", new SecureSessionTokens().digest(valid), new SecureSessionTokens().generate()))
            assertEquals(401, session("GET", invalid).statusCode());
        assertEquals(200, session("GET", valid).statusCode());
    }

    private HttpResponse<String> login(String username, String password) throws Exception {
        return request(client, "POST", "/sessions", loginBody(username, password), null, "application/json");
    }

    private String loginToken(String username) throws Exception {
        var response = login(username, PASSWORD);
        assertEquals(201, response.statusCode());
        return json(response).get("token").getAsString();
    }

    private String loginBody(String username, String password) {
        return gson.toJson(Map.of("username", username, "password", password));
    }

    private HttpResponse<String> session(String method, String token) throws Exception {
        return request(client, method, "/sessions/current", "", "Bearer " + token, "application/json");
    }

    private HttpResponse<String> request(HttpClient browser, String method, String path, String body,
                                         String authorization, String contentType) throws Exception {
        var request = HttpRequest.newBuilder(server.uri().resolve(path)).header("Content-Type", contentType)
                .method(method, HttpRequest.BodyPublishers.ofString(body));
        if (authorization != null) request.header("Authorization", authorization);
        return browser.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonObject json(HttpResponse<String> response) {
        assertTrue(response.headers().firstValue("Content-Type").orElseThrow().startsWith("application/json"));
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    private int sessionCount() throws Exception {
        try (var connection = database.openConnection();
             var query = connection.prepareStatement("SELECT count(*) FROM authentication_sessions");
             var rows = query.executeQuery()) {
            assertTrue(rows.next());
            return rows.getInt(1);
        }
    }
}
