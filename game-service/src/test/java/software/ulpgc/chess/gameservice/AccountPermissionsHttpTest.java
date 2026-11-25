package software.ulpgc.chess.gameservice;

import com.google.gson.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import software.ulpgc.chess.authenticationservice.application.usecases.AuthenticationUseCases;
import software.ulpgc.chess.authenticationservice.infrastructure.adapters.*;
import software.ulpgc.chess.gameservice.application.usecases.*;
import software.ulpgc.chess.gameservice.infrastructure.adapters.*;
import software.ulpgc.chess.gameservice.infrastructure.http.RoomJsonCodec;
import software.ulpgc.chess.playerservice.application.usecases.*;
import software.ulpgc.chess.playerservice.infrastructure.adapters.PlayerApiHandler;
import software.ulpgc.chess.playerservice.infrastructure.adapters.PlayerHttpServer;
import software.ulpgc.chess.playerservice.infrastructure.adapters.SqlitePlayerDatabase;
import software.ulpgc.chess.playerservice.infrastructure.adapters.SqlitePlayerRepository;
import software.ulpgc.chess.registerservice.application.usecases.AccountUseCases;
import software.ulpgc.chess.registerservice.infrastructure.adapters.*;

import java.net.*;
import java.net.http.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class AccountPermissionsHttpTest {
    private static final String PASSWORD = "white knight on e4";
    @TempDir Path directory;
    private final AtomicLong time = new AtomicLong(Instant.parse("2026-11-24T12:00:00Z").toEpochMilli());
    private final InstantSource instants = () -> Instant.ofEpochMilli(time.get());
    private final Clock clock = instants.withZone(ZoneOffset.UTC);
    private final Gson gson = new Gson();
    private final Browser alice = browser();
    private final Browser bob = browser();
    private final Browser other = browser();
    private AuthenticationHttpServer authentication;
    private PlayerHttpServer players;
    private RegisterHttpServer registration;
    private GameHttpServer game;
    private SqliteAuthenticationDatabase authenticationDatabase;
    private SqliteRoomRepository rooms;
    private HttpPlayerService playerClient;
    private HttpAccountServices accountClient;
    private HttpPlayerIdentity registrationPlayers;
    private software.ulpgc.chess.playerservice.infrastructure.adapters.HttpAccountIdentity playerAccounts;
    private HttpAccountIdentity registrationAccounts;

    @BeforeEach void start() {
        var accountsDatabase = new SqliteAccountDatabase(directory.resolve("accounts.sqlite"));
        accountsDatabase.initializeSchema();
        authenticationDatabase = new SqliteAuthenticationDatabase(directory.resolve("accounts.sqlite"));
        authenticationDatabase.initializeSchema();
        startAuthentication(0);
        var profilesDatabase = new SqlitePlayerDatabase(directory.resolve("players.sqlite"));
        profilesDatabase.initializeSchema();
        var profiles = new SqlitePlayerRepository(profilesDatabase);
        var resolve = new ResolvePlayerUseCase(profiles);
        playerAccounts = new software.ulpgc.chess.playerservice.infrastructure.adapters.HttpAccountIdentity(authentication.uri());
        players = new PlayerHttpServer(0, new PlayerApiHandler(resolve, new RenamePlayerUseCase(resolve, profiles),
                new AccountProfileUseCases(profiles, playerAccounts)));
        players.start();
        registrationPlayers = new HttpPlayerIdentity(players.uri());
        registrationAccounts = new HttpAccountIdentity(authentication.uri());
        registration = new RegisterHttpServer(0, new AccountApiHandler(new AccountUseCases(new SqliteAccountRepository(accountsDatabase),
                registrationPlayers, new Argon2PasswordHasher(), registrationAccounts)));
        registration.start();
        playerClient = new HttpPlayerService(players.uri());
        accountClient = new HttpAccountServices(registration.uri(), authentication.uri());
        var roomsDatabase = new SqliteDatabase(directory.resolve("rooms.sqlite"));
        roomsDatabase.initialize();
        rooms = new SqliteRoomRepository(roomsDatabase);
        startGame();
    }

    private void startAuthentication(int port) {
        var cases = new AuthenticationUseCases(new SqliteAccountCredentialsReader(authenticationDatabase), new Argon2PasswordVerifier(),
                new SqliteSessionRepository(authenticationDatabase), new SecureSessionTokens(), clock, Duration.ofHours(8), Duration.ofMinutes(30));
        authentication = new AuthenticationHttpServer(port, new AuthenticationApiHandler(cases));
        authentication.start();
    }

    private void startGame() {
        var access = new AccountAccessUseCases(accountClient, accountClient);
        var sessions = new BrowserSessions(playerClient, access, clock);
        var codec = new RoomJsonCodec();
        var create = new CreateRoomUseCase(rooms);
        var get = new GetRoomUseCase(rooms);
        var move = new MoveUseCase(rooms);
        var api = new RoomApiHandler(create, get, new EnterRoomUseCase(get, new JoinRoomUseCase(rooms)), move, sessions, codec);
        game = new GameHttpServer(0, api, new GameApiHandler(create, new CreateRoomOnceUseCase(rooms), get, move, sessions, codec),
                new PlayerProfileApiHandler(new PlayerProfileUseCases(playerClient, playerClient, playerClient), sessions, codec),
                new BrowserAccountApiHandler(access, sessions));
        game.start();
    }

    @AfterEach void stop() {
        game.close();
        accountClient.close();
        playerClient.close();
        registration.close();
        registrationPlayers.close();
        registrationAccounts.close();
        players.close();
        playerAccounts.close();
        authentication.close();
        alice.close(); bob.close(); other.close();
    }

    @Test void registrationPreservesTheGuestMatchAndOldCredentialsCannotBypassLogin() throws Exception {
        JsonObject state = state(alice);
        String playerId = state.getAsJsonObject("viewer").get("id").getAsString();
        String oldCredential = alice.cookie("regalia");
        String oldToken = state.get("token").getAsString();
        String code = createAndJoin(state);
        var saved = rooms.findByCode(code).orElseThrow();

        var created = request(alice, "/api/accounts", "POST", Map.of("token", oldToken, "username", "alice", "password", PASSWORD,
                "credential", bob.cookie("regalia"), "playerId", "someone-else"));

        assertEquals(201, created.statusCode());
        assertTrue(json(created).get("requiresLogin").getAsBoolean());
        assertFalse(created.body().contains(oldCredential));
        assertTrue(created.headers().allValues("Set-Cookie").isEmpty());
        assertEquals(401, send(alice, "/api/state", "GET", null).statusCode());
        assertEquals(401, request(alice, "/games/" + code, "PUT", Map.of("token", oldToken, "from", "e2", "to", "e4")).statusCode());
        assertEquals(401, legacy(alice, Map.of("token", oldToken, "action", "move", "from", "e2", "to", "e4")).statusCode());
        assertEquals(401, send(alice, "/api/profile", "GET", null).statusCode());
        var signedIn = login(alice, "alice");
        assertEquals(playerId, signedIn.getAsJsonObject("viewer").get("id").getAsString());
        assertNotEquals(oldToken, signedIn.get("token").getAsString());
        assertNotEquals(oldCredential, alice.cookie("regalia"));
        var continued = state(alice);
        assertEquals(code, continued.getAsJsonObject("room").get("code").getAsString());
        assertEquals(200, request(alice, "/games/" + code, "PUT", Map.of("token", continued.get("token").getAsString(), "from", "e2", "to", "e4")).statusCode());
        assertEquals(saved.white(), rooms.findByCode(code).orElseThrow().white());
        assertEquals(1, rooms.findByCode(code).orElseThrow().game().moves().size());
        assertOldGuestIsBlocked(oldCredential);
    }

    @Test void anotherBrowserRecoversTheProfileAndIdentityWithoutTheOriginalGuestCredential() throws Exception {
        var first = state(alice);
        String id = first.getAsJsonObject("viewer").get("id").getAsString();
        String code = createAndJoin(first);
        var recorded = rooms.findByCode(code).orElseThrow();
        assertEquals(200, request(alice, "/api/profile", "PUT", Map.of("token", token(first), "name", "Saved Alice")).statusCode());
        register(alice, "alice");
        login(alice, "alice");
        String originalCredential = alice.cookie("regalia");
        var signedIn = login(other, "alice");
        assertNotEquals(originalCredential, other.cookie("regalia"));
        var profile = json(send(other, "/api/profile", "GET", null));
        assertEquals(id, profile.getAsJsonObject("profile").get("id").getAsString());
        assertEquals("Saved Alice", profile.getAsJsonObject("profile").get("name").getAsString());
        assertEquals(200, request(other, "/api/profile", "PUT", Map.of("token", token(signedIn), "name", "Alice Updated", "id", "other")).statusCode());
        assertEquals("Alice Updated", json(send(alice, "/api/profile", "GET", null)).getAsJsonObject("profile").get("name").getAsString());
        assertEquals(recorded, rooms.findByCode(code).orElseThrow());
        game.close(); startGame();
        var restored = session(other);
        assertTrue(restored.get("authenticated").getAsBoolean());
        assertEquals(id, restored.getAsJsonObject("viewer").get("id").getAsString());
        assertEquals(200, request(other, "/games/" + code, "PUT", Map.of("token", token(restored), "from", "e2", "to", "e4")).statusCode());
    }

    @Test void logoutAndDeactivationCannotRecoverHistoricalPermissionsThroughGuestRoutes() throws Exception {
        var initial = state(alice);
        String id = initial.getAsJsonObject("viewer").get("id").getAsString();
        String oldCredential = alice.cookie("regalia");
        String code = createAndJoin(initial);
        register(alice, "alice");
        login(alice, "alice");
        var current = login(other, "alice");
        var saved = rooms.findByCode(code).orElseThrow();
        assertEquals(200, request(other, "/api/session", "DELETE", Map.of("token", token(current))).statusCode());
        var guest = state(other);
        assertNotEquals(id, guest.getAsJsonObject("viewer").get("id").getAsString());
        assertEquals(400, request(other, "/games/" + code, "PUT", Map.of("token", token(guest), "from", "e2", "to", "e4")).statusCode());
        current = login(other, "alice");
        var badPassword = request(other, "/api/accounts/current", "DELETE", Map.of("token", token(current), "password", "the wrong chess password"));
        assertEquals(401, badPassword.statusCode());
        assertTrue(badPassword.headers().allValues("Set-Cookie").isEmpty());
        assertEquals(200, request(other, "/api/accounts/current", "DELETE", Map.of("token", token(current), "password", PASSWORD)).statusCode());
        assertEquals(401, send(alice, "/api/state", "GET", null).statusCode());
        assertTrue(session(alice).get("requiresLogin").getAsBoolean());
        assertEquals(401, send(alice, "/api/profile", "GET", null).statusCode());
        assertEquals(saved, rooms.findByCode(code).orElseThrow());
        assertEquals(200, send(alice, "/games/" + code, "GET", null).statusCode());
        assertOldGuestIsBlocked(oldCredential);
        game.close(); startGame();
        assertOldGuestIsBlocked(oldCredential);
    }

    @Test void invalidTokensCrossSessionTokensAndLoginFailuresPreserveTheBrowserSession() throws Exception {
        var before = state(alice);
        var another = state(other);
        String guestCookie = alice.cookie("regalia");
        var wrongToken = request(alice, "/api/accounts", "POST", Map.of("token", token(another), "username", "alice", "password", PASSWORD));
        assertEquals(403, wrongToken.statusCode());
        assertTrue(wrongToken.headers().allValues("Set-Cookie").isEmpty());
        register(alice, "alice");
        var invalid = request(alice, "/api/session", "POST", Map.of("token", token(before), "username", "alice", "password", "the wrong chess password"));
        assertEquals(401, invalid.statusCode());
        assertTrue(invalid.headers().allValues("Set-Cookie").isEmpty());
        assertEquals(guestCookie, alice.cookie("regalia"));
        assertEquals(token(before), token(session(alice)));
        var login = login(alice, "alice");
        assertEquals(403, request(alice, "/api/session", "DELETE", Map.of("token", token(before))).statusCode());
        assertTrue(session(alice).get("authenticated").getAsBoolean());
        assertEquals(token(login), token(session(alice)));
    }

    @Test void expiryAndBackendFailureNeverFallBackToTheGuestIdentityOrPretendLogoutSucceeded() throws Exception {
        var initial = state(alice);
        createAndJoin(initial);
        register(alice, "alice");
        var signedIn = login(alice, "alice");
        String cookie = alice.cookie("regalia-account");
        int port = authentication.uri().getPort();
        authentication.close();
        assertEquals(503, request(alice, "/api/session", "DELETE", Map.of("token", token(signedIn))).statusCode());
        assertEquals(cookie, alice.cookie("regalia-account"));
        assertEquals(503, send(alice, "/api/state", "GET", null).statusCode());
        startAuthentication(port);
        assertEquals(200, send(alice, "/api/state", "GET", null).statusCode());
        time.addAndGet(Duration.ofMinutes(30).toMillis());
        assertEquals(401, send(alice, "/api/state", "GET", null).statusCode());
        var expired = session(alice);
        assertTrue(expired.get("requiresLogin").getAsBoolean());
        assertFalse(state(bob).getAsJsonObject("presence").get("whiteConnected").getAsBoolean());
        assertEquals(200, request(alice, "/api/session", "DELETE", Map.of("token", token(expired))).statusCode());
        assertFalse(session(alice).get("authenticated").getAsBoolean());
    }

    @Test void logoutRevokesTheBoundAccountSessionEvenWhenItsCookieHasBeenRemoved() throws Exception {
        state(alice); register(alice, "alice");
        var signedIn = login(alice, "alice");
        String bearer = alice.cookie("regalia-account");
        String control = alice.cookie("regalia");
        try (var browserWithoutAccountCookie = HttpClient.newHttpClient()) {
            var logout = browserWithoutAccountCookie.send(HttpRequest.newBuilder(game.uri().resolve("/api/session"))
                    .header("Cookie", "regalia=" + control).header("Content-Type", "application/json")
                    .method("DELETE", HttpRequest.BodyPublishers.ofString(gson.toJson(Map.of("token", token(signedIn)))))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, logout.statusCode());
            var revoked = browserWithoutAccountCookie.send(HttpRequest.newBuilder(authentication.uri().resolve("/sessions/current"))
                    .header("Authorization", "Bearer " + bearer).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(401, revoked.statusCode());
        }
        assertEquals(401, send(alice, "/api/state", "GET", null).statusCode());
    }

    @Test void publicAccountWritesRejectUnboundedBodiesWrongTypesAndForeignOrigins() throws Exception {
        var current = session(alice);
        for (String body : List.of("{", "[]", "null", "{\"token\":12}"))
            assertEquals(400, send(alice, "/api/session", "POST", body).statusCode());
        assertEquals(413, send(alice, "/api/accounts", "POST", " ".repeat(8193)).statusCode());
        var wrongType = alice.client.send(HttpRequest.newBuilder(game.uri().resolve("/api/accounts"))
                .header("Content-Type", "text/plain").POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(415, wrongType.statusCode());
        var foreignOrigin = alice.client.send(HttpRequest.newBuilder(game.uri().resolve("/api/accounts"))
                .header("Content-Type", "application/json").header("Origin", "http://other.example")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(Map.of("token", token(current), "username", "alice", "password", PASSWORD))))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(403, foreignOrigin.statusCode());
        assertTrue(foreignOrigin.headers().allValues("Set-Cookie").isEmpty());
        assertEquals(token(current), token(session(alice)));
        assertFalse(new SqliteAccountRepository(new SqliteAccountDatabase(directory.resolve("accounts.sqlite")))
                .findByUsername("alice").isPresent());
    }

    @Test void aRegistrationLookupFailureCannotAuthorizeGuestMovesOrChangeHistory() throws Exception {
        var current = state(alice);
        String code = createAndJoin(current);
        var saved = rooms.findByCode(code).orElseThrow();
        registration.close();

        var failed = request(alice, "/games/" + code, "PUT", Map.of("token", token(current), "from", "e2", "to", "e4"));

        assertEquals(503, failed.statusCode());
        assertEquals(saved, rooms.findByCode(code).orElseThrow());
        assertTrue(failed.headers().allValues("Set-Cookie").isEmpty());
        var unavailableBootstrap = send(other, "/api/session", "GET", null);
        assertEquals(503, unavailableBootstrap.statusCode());
        assertTrue(unavailableBootstrap.headers().allValues("Set-Cookie").isEmpty());
    }

    @Test void keyedCreationUsesTheAccountIdentityAndExpiredAccountsCannotRetryIt() throws Exception {
        register(alice, "alice");
        var signedIn = login(alice, "alice");
        var request = Map.of("token", token(signedIn), "name", "Recorded Alice", "operationKey", "account-key",
                "code", "KEY001", "playerId", "forged-player", "gameId", "forged-game");
        var first = request(alice, "/games", "POST", request);
        assertEquals(201, first.statusCode());
        var saved = rooms.findByCode("KEY001").orElseThrow();
        assertEquals(signedIn.getAsJsonObject("viewer").get("id").getAsString(), saved.white().id());
        var anotherBrowser = login(other, "alice");
        assertEquals(201, request(other, "/games", "POST", Map.of("token", token(anotherBrowser), "name", "Recorded Alice",
                "operationKey", "account-key", "code", "KEY001")).statusCode());
        time.addAndGet(Duration.ofMinutes(30).toMillis());
        assertEquals(401, request(alice, "/games", "POST", request).statusCode());
        assertEquals(saved, rooms.findByCode("KEY001").orElseThrow());
    }

    private String createAndJoin(JsonObject aliceState) throws Exception {
        var created = request(alice, "/games", "POST", Map.of("token", token(aliceState), "name", "Recorded Alice"));
        assertEquals(201, created.statusCode());
        String code = json(created).get("id").getAsString();
        var second = state(bob);
        assertEquals(200, legacy(bob, Map.of("token", token(second), "action", "join", "code", code, "name", "Recorded Bob")).statusCode());
        return code;
    }

    private void register(Browser browser, String username) throws Exception {
        var current = session(browser);
        assertEquals(201, request(browser, "/api/accounts", "POST", Map.of("token", token(current), "username", username, "password", PASSWORD)).statusCode());
    }

    private JsonObject login(Browser browser, String username) throws Exception {
        var current = session(browser);
        var response = request(browser, "/api/session", "POST", Map.of("token", token(current), "username", username, "password", PASSWORD));
        assertEquals(201, response.statusCode(), response.body());
        assertTrue(response.headers().allValues("Set-Cookie").stream().allMatch(cookie -> cookie.contains("HttpOnly") && cookie.contains("SameSite=Lax")));
        String secret = browser.cookie("regalia-account");
        assertFalse(response.body().contains(secret));
        assertFalse(response.body().contains(PASSWORD));
        return json(response);
    }

    private void assertOldGuestIsBlocked(String credential) throws Exception {
        try (var attacker = HttpClient.newHttpClient()) {
            var response = attacker.send(HttpRequest.newBuilder(game.uri().resolve("/api/state"))
                    .header("Cookie", "regalia=" + credential).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(401, response.statusCode());
        }
    }

    private JsonObject state(Browser browser) throws Exception {
        var response = send(browser, "/api/state", "GET", null);
        assertEquals(200, response.statusCode(), response.body());
        return json(response);
    }
    private JsonObject session(Browser browser) throws Exception {
        var response = send(browser, "/api/session", "GET", null);
        assertEquals(200, response.statusCode(), response.body());
        return json(response);
    }
    private String token(JsonObject response) { return response.get("token").getAsString(); }
    private JsonObject json(HttpResponse<String> response) { return JsonParser.parseString(response.body()).getAsJsonObject(); }
    private HttpResponse<String> request(Browser browser, String path, String method, Map<String, String> fields) throws Exception {
        return send(browser, path, method, gson.toJson(fields));
    }
    private HttpResponse<String> send(Browser browser, String path, String method, String body) throws Exception {
        return browser.client.send(HttpRequest.newBuilder(game.uri().resolve(path)).header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> legacy(Browser browser, Map<String, String> fields) throws Exception {
        String body = fields.entrySet().stream().map(entry -> URLEncoder.encode(entry.getKey(), java.nio.charset.StandardCharsets.UTF_8)
                + "=" + URLEncoder.encode(entry.getValue(), java.nio.charset.StandardCharsets.UTF_8)).collect(java.util.stream.Collectors.joining("&"));
        return browser.client.send(HttpRequest.newBuilder(game.uri().resolve("/api/action"))
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    private static Browser browser() {
        var cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        return new Browser(HttpClient.newBuilder().cookieHandler(cookies).build(), cookies);
    }
    private record Browser(HttpClient client, CookieManager cookies) implements AutoCloseable {
        String cookie(String name) {
            return cookies.getCookieStore().getCookies().stream().filter(cookie -> cookie.getName().equals(name))
                    .findFirst().orElseThrow().getValue();
        }
        @Override public void close() { client.close(); }
    }
}
