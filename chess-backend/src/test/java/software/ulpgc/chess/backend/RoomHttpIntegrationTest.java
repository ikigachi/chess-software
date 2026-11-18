package software.ulpgc.chess.backend;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.javalin.Javalin;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.ulpgc.chess.application.domain.model.*;
import software.ulpgc.chess.backend.infrastructure.adapters.*;
import software.ulpgc.chess.gameservice.application.ports.RoomRepository;
import software.ulpgc.chess.gameservice.application.usecases.*;
import software.ulpgc.chess.gameservice.infrastructure.adapters.*;
import software.ulpgc.chess.gameservice.infrastructure.http.*;
import software.ulpgc.chess.playerservice.application.usecases.ResolvePlayerUseCase;
import software.ulpgc.chess.playerservice.application.usecases.RenamePlayerUseCase;
import software.ulpgc.chess.playerservice.infrastructure.adapters.InMemoryPlayerRepository;
import software.ulpgc.chess.playerservice.infrastructure.adapters.PlayerApiHandler;
import software.ulpgc.chess.playerservice.infrastructure.adapters.PlayerHttpServer;
import software.ulpgc.chess.playerservice.infrastructure.adapters.SqlitePlayerDatabase;
import software.ulpgc.chess.playerservice.infrastructure.adapters.SqlitePlayerRepository;
import software.ulpgc.chess.playerservice.application.ports.PlayerRepository;

import java.net.*;
import java.net.http.*;
import java.nio.file.Path;
import java.time.Instant;
import java.time.Clock;
import java.time.InstantSource;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class RoomHttpIntegrationTest {
    private RoomHttpServer server;
    private GameHttpServer gameServer;
    private PlayerHttpServer playerServer;
    private HttpPlayerService playerIdentity;
    private GameServiceProxy proxy;
    private GetRoomUseCase rooms;
    private final HttpClient white = browser();
    private final HttpClient black = browser();

    @BeforeEach void start() throws Exception {
        start(new InMemoryRoomRepository());
    }

    private void start(RoomRepository repository) throws Exception {
        start(repository, Clock.systemUTC());
    }

    private void start(RoomRepository repository, Clock clock) throws Exception {
        playerServer = createPlayerHttpServer(0);
        playerServer.start();
        playerIdentity = new HttpPlayerService(playerServer.uri());
        BrowserSessions sessions = new BrowserSessions(playerIdentity, clock);
        rooms = new GetRoomUseCase(repository);
        RoomApiHandler api = new RoomApiHandler(new CreateRoomUseCase(repository), rooms,
                new EnterRoomUseCase(rooms, new JoinRoomUseCase(repository)),
                new MoveUseCase(repository), sessions, new RoomJsonCodec());
        gameServer = new GameHttpServer(0, api,
                new GameApiHandler(new CreateRoomUseCase(repository), rooms, new MoveUseCase(repository), sessions, new RoomJsonCodec()),
                new PlayerProfileApiHandler(new PlayerProfileUseCases(playerIdentity, playerIdentity), sessions, new RoomJsonCodec()));
        gameServer.start();
        proxy = new GameServiceProxy(gameServer.uri());
        server = new RoomHttpServer(0, proxy, new StaticAssetHandler());
        server.start();
    }

    private PlayerHttpServer createPlayerHttpServer(int port) {
        return createPlayerHttpServer(port, new InMemoryPlayerRepository());
    }

    private PlayerHttpServer createPlayerHttpServer(int port, PlayerRepository repository) {
        var resolve = new ResolvePlayerUseCase(repository);
        return new PlayerHttpServer(port, new PlayerApiHandler(resolve, new RenamePlayerUseCase(resolve, repository)));
    }

    @AfterEach void stop() {
        if (server != null) server.close();
        if (proxy != null) proxy.close();
        if (gameServer != null) gameServer.close();
        if (playerIdentity != null) playerIdentity.close();
        if (playerServer != null) playerServer.close();
    }

    private static HttpClient browser() {
        return HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
    }

    private HttpResponse<String> get(HttpClient client, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(server.uri().resolve(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(HttpClient client, Map<String, String> fields) throws Exception {
        return client.send(HttpRequest.newBuilder(server.uri().resolve("/api/action"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(FormData.encode(fields))).build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonObject data(HttpResponse<String> response) {
        String contentType = response.headers().firstValue("Content-Type").orElseThrow();
        assertEquals("application/json;charset=utf-8", contentType.replace(" ", "").toLowerCase(java.util.Locale.ROOT));
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    private String token(HttpClient client) throws Exception {
        return data(get(client, "/api/state")).get("token").getAsString();
    }

    private HttpResponse<String> move(HttpClient client, String token, String from, String to) throws Exception {
        return post(client, Map.of("token", token, "action", "move", "from", from, "to", to));
    }

    private HttpResponse<String> postGame(HttpClient client, String json) throws Exception {
        return client.send(HttpRequest.newBuilder(server.uri().resolve("/games"))
                .header("Content-Type", "application/json; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> createGame(HttpClient client, String token, String name) throws Exception {
        JsonObject request = new JsonObject();
        request.addProperty("name", name);
        request.addProperty("token", token);
        return postGame(client, request.toString());
    }

    private HttpResponse<String> putGame(HttpClient client, String code, Map<String, String> fields) throws Exception {
        JsonObject request = new JsonObject();
        fields.forEach(request::addProperty);
        return putGame(client, server.uri().resolve("/games/" + code), request.toString());
    }

    private HttpResponse<String> putGame(HttpClient client, URI uri, String json) throws Exception {
        return client.send(HttpRequest.newBuilder(uri).header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> updatePlayerProfile(HttpClient client, String token, String name) throws Exception {
        JsonObject request = new JsonObject();
        request.addProperty("token", token);
        request.addProperty("name", name);
        return sendProfileUpdateJson(client, request.toString());
    }

    private HttpResponse<String> sendProfileUpdateJson(HttpClient client, String json) throws Exception {
        return client.send(HttpRequest.newBuilder(server.uri().resolve("/api/profile"))
                .header("Content-Type", "application/json").PUT(HttpRequest.BodyPublishers.ofString(json)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private String readBrowserSessionCredential(HttpClient client) {
        var cookies = assertInstanceOf(CookieManager.class, client.cookieHandler().orElseThrow()).getCookieStore().getCookies();
        return cookies.stream().filter(cookie -> cookie.getName().equals("regalia"))
                .findFirst().orElseThrow().getValue();
    }

    @Test void updatesTheSessionProfileAndPersistsItWithoutChangingTheSavedGame(@TempDir Path directory) throws Exception {
        int port = playerServer.uri().getPort();
        playerServer.close();
        var database = new SqlitePlayerDatabase(directory.resolve("players.sqlite"));
        database.initializeSchema();
        playerServer = createPlayerHttpServer(port, new SqlitePlayerRepository(database));
        playerServer.start();
        String whiteToken = token(white);
        String blackToken = token(black);
        String code = data(createGame(white, whiteToken, "Alice")).get("id").getAsString();
        assertEquals(200, post(black, Map.of("token", blackToken, "action", "join", "name", "Bob", "code", code)).statusCode());
        assertEquals(200, putGame(white, code, Map.of("token", whiteToken, "from", "e2", "to", "e4")).statusCode());
        var before = data(get(white, "/api/state"));
        Room saved = rooms.execute(code);

        var response = updatePlayerProfile(white, whiteToken, " Renamed ");

        assertEquals(200, response.statusCode());
        var updated = data(response);
        assertEquals("Renamed", updated.getAsJsonObject("profile").get("name").getAsString());
        assertEquals(saved.white().id(), updated.getAsJsonObject("profile").get("id").getAsString());
        assertEquals(whiteToken, updated.get("token").getAsString());
        assertFalse(response.body().contains(readBrowserSessionCredential(white)));
        var current = data(get(white, "/api/state"));
        assertEquals(updated.get("profile"), current.get("viewer"));
        assertEquals(before.get("room"), current.get("room"));
        assertEquals(before.get("pieces"), current.get("pieces"));
        assertSame(saved, rooms.execute(code));
        assertEquals("Guest", data(get(black, "/api/profile")).getAsJsonObject("profile").get("name").getAsString());

        playerServer.close();
        var restarted = new SqlitePlayerDatabase(directory.resolve("players.sqlite"));
        restarted.initializeSchema();
        playerServer = createPlayerHttpServer(port, new SqlitePlayerRepository(restarted));
        playerServer.start();

        assertEquals(updated, data(get(white, "/api/profile")));
        assertEquals(new User(saved.white().id(), "Renamed"), new SqlitePlayerRepository(restarted).findById(saved.white().id()).orElseThrow());
        assertSame(saved, rooms.execute(code));
    }

    @Test void rejectsInvalidProfileUpdatesAndIgnoresClaimedPlayerIdentities() throws Exception {
        String whiteToken = token(white);
        String blackToken = token(black);
        assertEquals(200, updatePlayerProfile(white, whiteToken, "Alice").statusCode());
        assertEquals(200, updatePlayerProfile(black, blackToken, "Bob").statusCode());
        var original = data(get(white, "/api/state"));
        var otherProfile = data(get(black, "/api/profile"));
        assertEquals(403, updatePlayerProfile(white, "wrong", " ").statusCode());
        assertEquals(403, sendProfileUpdateJson(white, "{}").statusCode());
        for (String invalid : List.of("{", "[]", "null", "{} {}", "{\"name\":23}",
                "{\"token\":\"" + whiteToken + "\"}")) {
            var response = sendProfileUpdateJson(white, invalid);
            assertEquals(400, response.statusCode(), invalid);
            assertEquals(whiteToken, data(response).get("token").getAsString());
        }
        assertEquals(400, updatePlayerProfile(white, whiteToken, "x".repeat(41)).statusCode());
        var wrongType = white.send(HttpRequest.newBuilder(server.uri().resolve("/api/profile"))
                .header("Content-Type", "text/plain").PUT(HttpRequest.BodyPublishers.ofString("{}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(415, wrongType.statusCode());
        assertEquals(413, sendProfileUpdateJson(white, "x".repeat(8193)).statusCode());
        assertEquals(original, data(get(white, "/api/state")));
        assertEquals(otherProfile, data(get(black, "/api/profile")));

        JsonObject claimedIdentity = new JsonObject();
        claimedIdentity.addProperty("token", whiteToken);
        claimedIdentity.addProperty("name", "Only Alice changes");
        claimedIdentity.addProperty("id", otherProfile.getAsJsonObject("profile").get("id").getAsString());
        claimedIdentity.addProperty("credential", readBrowserSessionCredential(black));
        assertEquals(200, sendProfileUpdateJson(white, claimedIdentity.toString()).statusCode());
        assertEquals("Only Alice changes", data(get(white, "/api/profile")).getAsJsonObject("profile").get("name").getAsString());
        assertEquals(otherProfile, data(get(black, "/api/profile")));
        var wrongMethod = white.send(HttpRequest.newBuilder(server.uri().resolve("/api/profile"))
                .DELETE().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(405, wrongMethod.statusCode());
        assertEquals("GET, PUT", wrongMethod.headers().firstValue("Allow").orElseThrow());
    }

    @Test void missingAndExpiredProfileSessionsAreRejectedWithoutIssuingCookies() throws Exception {
        stop();
        AtomicLong elapsed = new AtomicLong();
        InstantSource time = () -> Instant.ofEpochMilli(elapsed.get());
        start(new InMemoryRoomRepository(), time.withZone(ZoneOffset.UTC));
        for (var response : List.of(get(white, "/api/profile"), updatePlayerProfile(white, "wrong", "Alice"))) {
            assertEquals(401, response.statusCode());
            assertTrue(response.headers().firstValue("Set-Cookie").isEmpty());
            assertTrue(data(response).get("token").isJsonNull());
        }
        String oldToken = token(white);
        assertEquals(200, updatePlayerProfile(white, oldToken, "Alice").statusCode());
        elapsed.set(8 * 60 * 60 * 1000L + 1);
        assertEquals(401, get(white, "/api/profile").statusCode());
        assertEquals(401, updatePlayerProfile(white, oldToken, "Renamed").statusCode());
        String newToken = token(white);
        assertNotEquals(oldToken, newToken);
        assertEquals(403, updatePlayerProfile(white, oldToken, "Renamed").statusCode());
        assertEquals("Alice", data(get(white, "/api/profile")).getAsJsonObject("profile").get("name").getAsString());
    }

    @Test void unavailablePlayerServicePreservesTheSessionProfileUntilRecovery() throws Exception {
        String token = token(white);
        assertEquals(200, updatePlayerProfile(white, token, "Alice").statusCode());
        var original = data(get(white, "/api/state"));
        int port = playerServer.uri().getPort();
        playerServer.close();

        var failure = updatePlayerProfile(white, token, "Renamed");

        assertEquals(503, failure.statusCode());
        assertEquals(token, data(failure).get("token").getAsString());
        assertEquals(original, data(get(white, "/api/state")));
        playerServer = createPlayerHttpServer(port);
        playerServer.start();
        assertEquals(200, updatePlayerProfile(white, token, "Recovered").statusCode());
        assertEquals("Recovered", data(get(white, "/api/profile")).getAsJsonObject("profile").get("name").getAsString());
    }

    @Test void reloadsTheProfileWhenTheNameWasSavedButTheAcknowledgementWasInvalid() throws Exception {
        String token = token(white);
        var original = data(get(white, "/api/state"));
        String playerId = original.getAsJsonObject("viewer").get("id").getAsString();
        int port = playerServer.uri().getPort();
        playerServer.close();
        playerServer = null;
        AtomicReference<String> savedProfile = new AtomicReference<>();
        AtomicReference<String> receivedCredential = new AtomicReference<>();
        Javalin remote = Javalin.create(config -> {
            config.routes.put("/players/name", context -> {
                var fields = JsonParser.parseString(new String(context.bodyInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
                receivedCredential.set(fields.get("credential").getAsString());
                JsonObject profile = new JsonObject();
                profile.addProperty("id", playerId);
                profile.addProperty("name", fields.get("name").getAsString());
                savedProfile.set(profile.toString());
                context.contentType("application/json").result("{");
            });
            config.routes.post("/players/resolve", context -> context.contentType("application/json").result(savedProfile.get()));
        }).start("127.0.0.1", port);
        try {
            assertEquals(503, updatePlayerProfile(white, token, "Saved remotely").statusCode());
            assertEquals(original, data(get(white, "/api/state")));
            assertEquals(readBrowserSessionCredential(white), receivedCredential.get());

            var reloaded = get(white, "/api/profile");

            assertEquals(200, reloaded.statusCode());
            assertEquals("Saved remotely", data(reloaded).getAsJsonObject("profile").get("name").getAsString());
            assertEquals(data(reloaded).get("profile"), data(get(white, "/api/state")).get("viewer"));
        } finally {
            remote.stop();
        }
    }

    @Test void servesStaticMarkupJavascriptAndAllPieceAssets() throws Exception {
        var response = get(white, "/");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("A game, without haste."));
        assertTrue(response.body().contains("<script type=\"module\" src=\"/main.js\""));
        assertTrue(response.body().contains("id=\"square-template\""));
        assertFalse(response.body().contains("{{"));
        var preview = data(get(white, "/api/state"));
        assertTrue(preview.get("room").isJsonNull());
        assertEquals(32, preview.getAsJsonObject("pieces").size());
        assertEquals("Guest", preview.getAsJsonObject("viewer").get("name").getAsString());
        assertEquals(200, get(white, "/style.css").statusCode());
        var mainScript = get(white, "/main.js");
        assertEquals(200, mainScript.statusCode());
        assertTrue(mainScript.headers().firstValue("Content-Type").orElseThrow().startsWith("text/javascript"));
        assertEquals(200, get(white, "/app.js").statusCode());
        var roomActions = get(white, "/room-actions.js");
        assertEquals(200, roomActions.statusCode());
        assertTrue(roomActions.headers().firstValue("Content-Type").orElseThrow().startsWith("text/javascript"));
        assertEquals(200, get(white, "/room-api.js").statusCode());
        assertTrue(get(white, "/app.js").headers().firstValue("Content-Type").orElseThrow().startsWith("text/javascript"));
        for (String color : new String[]{"white", "black"}) {
            for (String piece : new String[]{"pawn", "rook", "knight", "bishop", "queen", "king"}) {
                var asset = get(white, "/assets/pieces/" + color + "-" + piece + ".svg");
                assertEquals(200, asset.statusCode());
                assertEquals("image/svg+xml", asset.headers().firstValue("Content-Type").orElseThrow());
            }
        }
        assertEquals(200, get(white, "/assets/marble-bust.png").statusCode());
        assertEquals(404, get(white, "/assets/pieces/white-dragon.svg").statusCode());
        assertEquals(404, get(white, "/unknown").statusCode());
        assertEquals(405, get(white, "/api/action").statusCode());
    }

    @Test void gameServiceCanRunIndependentlyAndItsFailureIsReportedByTheWebServer() throws Exception {
        var direct = white.send(HttpRequest.newBuilder(gameServer.uri().resolve("/api/state")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, direct.statusCode());
        assertEquals(32, data(direct).getAsJsonObject("pieces").size());
        var service = gameServer;
        gameServer = null;
        service.close();

        var unavailable = get(white, "/api/state");
        assertEquals(503, unavailable.statusCode());
        assertEquals("Game service is unavailable. Try again shortly.", data(unavailable).get("message").getAsString());
        assertEquals(200, get(white, "/").statusCode());
    }

    @Test void aPlayerServiceOutageRejectsNewSessionsButPreservesExistingPlayersAndGames() throws Exception {
        String whiteToken = token(white);
        String blackToken = token(black);
        String code = data(createGame(white, whiteToken, "Alice")).get("id").getAsString();
        assertEquals(200, post(black, Map.of("token", blackToken, "action", "join", "name", "Bob", "code", code)).statusCode());
        Room saved = rooms.execute(code);
        int playerPort = playerServer.uri().getPort();
        playerServer.close();

        HttpClient newcomer = browser();
        var rejected = get(newcomer, "/api/state");
        assertEquals(503, rejected.statusCode());
        assertTrue(rejected.headers().firstValue("Set-Cookie").isEmpty());
        assertTrue(data(rejected).get("token").isJsonNull());
        assertSame(saved, rooms.execute(code));
        assertEquals(200, putGame(white, code, Map.of("token", whiteToken, "from", "e2", "to", "e4")).statusCode());
        assertEquals(200, putGame(black, code, Map.of("token", blackToken, "from", "e7", "to", "e5")).statusCode());
        assertEquals(2, rooms.execute(code).game().moves().size());

        playerServer = createPlayerHttpServer(playerPort);
        playerServer.start();

        assertEquals(200, get(newcomer, "/api/state").statusCode());
        assertEquals(2, data(get(white, "/api/state")).getAsJsonObject("room").getAsJsonArray("moves").size());
    }

    @Test void profileRenamingSurvivesNewSessionsWithoutRewritingStoredParticipants(@TempDir Path directory) throws Exception {
        stop();
        AtomicLong elapsed = new AtomicLong();
        InstantSource time = () -> Instant.ofEpochMilli(elapsed.get());
        SqliteDatabase database = new SqliteDatabase(directory.resolve("rooms.sqlite"));
        database.initialize();
        start(new SqliteRoomRepository(database), time.withZone(ZoneOffset.UTC));
        String whiteToken = token(white);
        String code = data(createGame(white, whiteToken, "Alice")).get("id").getAsString();
        assertEquals(200, post(black, Map.of("token", token(black), "action", "join", "name", "Bob", "code", code)).statusCode());
        assertEquals(200, putGame(white, code, Map.of("token", whiteToken, "from", "e2", "to", "e4")).statusCode());
        var before = data(get(white, "/games/" + code));
        User original = rooms.execute(code).white();
        var cookies = assertInstanceOf(CookieManager.class, white.cookieHandler().orElseThrow()).getCookieStore().getCookies();
        String credential = cookies.stream().filter(cookie -> cookie.getName().equals("regalia"))
                .findFirst().orElseThrow().getValue();
        JsonObject request = new JsonObject();
        request.addProperty("credential", credential);
        request.addProperty("name", "Renamed");

        var response = white.send(HttpRequest.newBuilder(playerServer.uri().resolve("/players/name"))
                .header("Content-Type", "application/json").PUT(HttpRequest.BodyPublishers.ofString(request.toString())).build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertEquals(new User(original.id(), "Renamed"), playerIdentity.resolve(credential));
        assertEquals(before, data(get(white, "/games/" + code)));
        elapsed.set(8 * 60 * 60 * 1000L + 1);
        var reopened = data(get(white, "/api/state"));
        assertEquals(original.id(), reopened.getAsJsonObject("viewer").get("id").getAsString());
        assertEquals("Renamed", reopened.getAsJsonObject("viewer").get("name").getAsString());
        assertTrue(reopened.get("room").isJsonNull());
        String newToken = reopened.get("token").getAsString();
        assertEquals(200, post(white, Map.of("token", newToken, "action", "join", "name", "Renamed", "code", code)).statusCode());
        Room stored = new SqliteRoomRepository(database).findByCode(code).orElseThrow();
        assertEquals(original, stored.white());
        assertEquals(1, stored.game().moves().size());
        assertEquals(before, data(get(white, "/games/" + code)));
    }

    @Test void twoBrowserSessionsCreateJoinMoveAndRefresh() throws Exception {
        String whiteToken = token(white);
        String blackToken = token(black);
        var created = data(post(white, Map.of("token", whiteToken, "action", "create", "name", "Alice")));
        String code = created.getAsJsonObject("room").get("code").getAsString();
        assertTrue(created.getAsJsonObject("room").get("black").isJsonNull());
        assertTrue(created.getAsJsonObject("room").getAsJsonArray("moves").isEmpty());
        var joined = data(post(black, Map.of("token", blackToken, "action", "join", "name", "Bob", "code", code.toLowerCase()))).getAsJsonObject("room");
        assertEquals("Alice", joined.getAsJsonObject("white").get("name").getAsString());
        assertEquals("Bob", joined.getAsJsonObject("black").get("name").getAsString());
        assertEquals(400, post(white, Map.of("token", whiteToken, "action", "move", "from", "e2", "to", "e5")).statusCode());
        assertEquals(400, post(white, Map.of("token", whiteToken, "action", "move", "from", "a1", "to", "a4")).statusCode());
        var unchanged = data(get(white, "/api/state"));
        assertEquals("White", unchanged.getAsJsonObject("room").get("turn").getAsString());
        assertTrue(unchanged.getAsJsonObject("room").getAsJsonArray("moves").isEmpty());
        assertEquals("WhitePawn", unchanged.getAsJsonObject("pieces").get("e2").getAsString());
        var moved = post(white, Map.of("token", whiteToken, "action", "move", "from", "e2", "to", "e4"));
        assertEquals(200, moved.statusCode());
        var first = data(moved);
        assertEquals("Black", first.getAsJsonObject("room").get("turn").getAsString());
        assertEquals("WhitePawn", first.getAsJsonObject("pieces").get("e4").getAsString());
        assertFalse(first.getAsJsonObject("pieces").has("e2"));
        var rejected = post(white, Map.of("token", whiteToken, "action", "move", "from", "d2", "to", "d4"));
        assertEquals(400, rejected.statusCode());
        assertEquals("It is your opponent’s turn.", data(rejected).get("message").getAsString());
        var replied = data(post(black, Map.of("token", blackToken, "action", "move", "from", "e7", "to", "e5")));
        assertEquals("White", replied.getAsJsonObject("room").get("turn").getAsString());
        var moves = data(get(white, "/api/state")).getAsJsonObject("room").getAsJsonArray("moves");
        assertEquals(2, moves.size());
        assertEquals("e5", moves.get(1).getAsJsonObject().get("to").getAsString());
        assertEquals(2, rooms.execute(code).game().moves().size());
    }

    @Test void rejectsInvalidTokensAndKeepsUserTextOutOfTheHtmlDocument() throws Exception {
        String csrf = token(white);
        var invalid = post(white, Map.of("token", "wrong", "action", "create", "name", "Alice"));
        assertEquals(403, invalid.statusCode());
        assertEquals(csrf, data(invalid).get("token").getAsString());
        assertEquals(403, post(white, Map.of("token", "wrong", "action", "create", "name", " ")).statusCode());
        String name = "A <script>& {{TOKEN}}";
        var created = data(post(white, Map.of("token", csrf, "action", "create", "name", name)));
        assertEquals(name, created.getAsJsonObject("viewer").get("name").getAsString());
        assertEquals(name, created.getAsJsonObject("room").getAsJsonObject("white").get("name").getAsString());
        assertFalse(get(white, "/").body().contains(name));
        var waiting = post(white, Map.of("token", csrf, "action", "move", "from", "e2", "to", "e4"));
        assertEquals(400, waiting.statusCode());
        assertEquals("Wait for the second player to join.", data(waiting).get("message").getAsString());
        assertEquals(400, post(black, Map.of("token", token(black), "action", "join", "name", "Bob", "code", "MISSING")).statusCode());
        var left = data(post(white, Map.of("token", csrf, "action", "leave")));
        assertTrue(left.get("room").isJsonNull());
        assertNotNull(rooms.execute(created.getAsJsonObject("room").get("code").getAsString()));
    }

    @Test void retrievesAGameByIdWithoutJoiningOrChangingTheViewersRoom() throws Exception {
        String whiteToken = token(white);
        String blackToken = token(black);
        var created = data(post(white, Map.of("token", whiteToken, "action", "create", "name", "Alice")));
        String code = created.getAsJsonObject("room").get("code").getAsString();
        assertEquals(200, post(black, Map.of("token", blackToken, "action", "join", "name", "Bob", "code", code)).statusCode());
        var played = data(move(white, whiteToken, "e2", "e4"));
        Room original = rooms.execute(code);
        HttpClient viewer = browser();

        var response = get(viewer, "/games/" + code.toLowerCase(java.util.Locale.ROOT));
        assertEquals(200, response.statusCode());
        var game = data(response);
        assertEquals(code, game.get("id").getAsString());
        assertEquals(played.get("room"), game.get("room"));
        assertEquals(played.get("pieces"), game.get("pieces"));
        assertFalse(game.has("token"));
        assertTrue(response.headers().firstValue("Set-Cookie").isEmpty());
        assertTrue(data(get(viewer, "/api/state")).get("room").isJsonNull());
        assertSame(original, rooms.execute(code));
    }

    @Test void createsAGameFromJsonAndReturnsItsRetrievableLocation() throws Exception {
        String whiteToken = token(white);
        var response = createGame(white, whiteToken, " Zoë ");
        assertEquals(201, response.statusCode());
        var created = data(response);
        String id = created.get("id").getAsString();
        assertEquals("/games/" + id, response.headers().firstValue("Location").orElseThrow());
        assertEquals("Zoë", created.getAsJsonObject("room").getAsJsonObject("white").get("name").getAsString());
        assertEquals(32, created.getAsJsonObject("pieces").size());
        assertEquals(created, data(get(white, "/games/" + id)));
        var current = data(get(white, "/api/state"));
        assertEquals(created.get("room"), current.get("room"));
        assertEquals(created.get("pieces"), current.get("pieces"));
        assertEquals(200, post(black, Map.of("action", "join", "token", token(black), "name", "Bob", "code", id)).statusCode());
        assertEquals(200, move(white, whiteToken, "e2", "e4").statusCode());
    }

    @Test void invalidGameCreationPreservesTheExistingSessionAndGame() throws Exception {
        String token = token(white);
        var initial = createGame(white, token, "Alice");
        assertEquals(201, initial.statusCode());
        var original = data(get(white, "/api/state"));
        String code = original.getAsJsonObject("room").get("code").getAsString();
        Room saved = rooms.execute(code);

        assertEquals(403, createGame(white, "wrong", "Renamed").statusCode());
        assertEquals(400, createGame(white, token, " ").statusCode());
        for (String invalid : List.of("{", "[]", "null", "{} {}", "{\"name\":23}")) {
            assertEquals(400, postGame(white, invalid).statusCode(), invalid);
        }
        assertEquals(original, data(get(white, "/api/state")));
        assertSame(saved, rooms.execute(code));
    }

    @Test void gameCreationRequiresJsonAndLimitsTheRequestBody() throws Exception {
        var wrongType = white.send(HttpRequest.newBuilder(server.uri().resolve("/games"))
                .header("Content-Type", "text/plain").POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(415, wrongType.statusCode());
        assertEquals(413, postGame(white, "x".repeat(8193)).statusCode());
    }

    @Test void rejectsMissingGamesUnsupportedMethodsAndInvalidGameRoutes() throws Exception {
        var missing = get(white, "/games/MISSING");
        assertEquals(404, missing.statusCode());
        assertEquals("Game not found.", data(missing).get("message").getAsString());
        assertEquals(405, get(white, "/games").statusCode());
        for (String path : List.of("/games/", "/games/ABC123/moves", "/gamesABC123")) {
            assertEquals(404, get(white, path).statusCode(), path);
        }
        var unsupported = white.send(HttpRequest.newBuilder(server.uri().resolve("/games/ABC123"))
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(405, unsupported.statusCode());
        assertEquals("GET, PUT", unsupported.headers().firstValue("Allow").orElseThrow());
    }

    @Test void recordsJsonMovesThroughTheWebServerAndTheIndependentGameService() throws Exception {
        String whiteToken = token(white);
        String blackToken = token(black);
        String code = data(createGame(white, whiteToken, "Alice")).get("id").getAsString();
        assertEquals(200, post(black, Map.of("token", blackToken, "action", "join", "name", "Bob", "code", code)).statusCode());

        var response = putGame(white, code.toLowerCase(java.util.Locale.ROOT),
                Map.of("token", whiteToken, "from", "e2", "to", "e4"));
        assertEquals(200, response.statusCode());
        var first = data(response);
        assertEquals(code, first.get("id").getAsString());
        assertEquals("WhitePawn", first.getAsJsonObject("pieces").get("e4").getAsString());
        assertFalse(first.getAsJsonObject("pieces").has("e2"));
        assertEquals("Black", first.getAsJsonObject("room").get("turn").getAsString());
        assertEquals(first, data(get(black, "/games/" + code)));

        JsonObject reply = new JsonObject();
        reply.addProperty("token", blackToken);
        reply.addProperty("from", "e7");
        reply.addProperty("to", "e5");
        var direct = putGame(black, gameServer.uri().resolve("/games/" + code), reply.toString());
        assertEquals(200, direct.statusCode());
        var played = data(direct);
        assertEquals("White", played.getAsJsonObject("room").get("turn").getAsString());
        assertEquals(2, played.getAsJsonObject("room").getAsJsonArray("moves").size());
        assertEquals(played, data(get(white, "/games/" + code)));
        for (HttpClient player : List.of(white, black)) {
            var snapshot = data(get(player, "/api/state"));
            assertEquals(played.get("room"), snapshot.get("room"));
            assertEquals(played.get("pieces"), snapshot.get("pieces"));
        }
    }

    @Test void invalidJsonMovesPreserveTheGameAndBrowserSessions() throws Exception {
        String whiteToken = token(white);
        String blackToken = token(black);
        String code = data(createGame(white, whiteToken, "Alice")).get("id").getAsString();
        assertEquals(200, post(black, Map.of("token", blackToken, "action", "join", "name", "Bob", "code", code)).statusCode());
        var original = data(get(white, "/api/state"));
        Room saved = rooms.execute(code);
        URI uri = server.uri().resolve("/games/" + code);

        assertEquals(403, putGame(white, code, Map.of("token", "wrong", "from", "invalid", "to", "e4")).statusCode());
        assertEquals(400, putGame(white, code, Map.of("token", whiteToken, "from", "e2", "to", "e5")).statusCode());
        assertEquals(400, putGame(white, code, Map.of("token", whiteToken, "from", "a1", "to", "a4")).statusCode());
        assertEquals(400, putGame(white, code, Map.of("token", whiteToken, "from", "e2", "to", "e4", "promotion", "king")).statusCode());
        assertEquals(400, putGame(white, code, Map.of("token", whiteToken, "from", "e2")).statusCode());
        assertEquals(400, putGame(black, code, Map.of("token", blackToken, "from", "e7", "to", "e5")).statusCode());
        for (String invalid : List.of("{", "[]", "null", "{} {}", "{\"from\":23}")) {
            var response = putGame(white, uri, invalid);
            assertEquals(400, response.statusCode(), invalid);
            assertTrue(data(response).has("message"));
        }
        HttpClient outsider = browser();
        assertEquals(400, putGame(outsider, code, Map.of("token", token(outsider), "from", "e2", "to", "e4")).statusCode());
        assertTrue(data(get(outsider, "/api/state")).get("room").isJsonNull());
        assertEquals(original, data(get(white, "/api/state")));
        assertSame(saved, rooms.execute(code));
    }

    @Test void jsonMovesRequireAnExistingGameJsonContentAndALimitedBody() throws Exception {
        String whiteToken = token(white);
        var missing = putGame(white, "MISSING", Map.of("token", whiteToken, "from", "e2", "to", "e4"));
        assertEquals(404, missing.statusCode());
        assertEquals("Game not found.", data(missing).get("message").getAsString());
        assertTrue(data(get(white, "/api/state")).get("room").isJsonNull());
        URI uri = server.uri().resolve("/games/MISSING");
        var wrongType = white.send(HttpRequest.newBuilder(uri).header("Content-Type", "text/plain")
                .PUT(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(415, wrongType.statusCode());
        assertEquals("Expected application/json.", data(wrongType).get("message").getAsString());
        var oversized = putGame(white, uri, "x".repeat(8193));
        assertEquals(413, oversized.statusCode());
        assertEquals("Request too large", data(oversized).get("message").getAsString());
        var directOversized = putGame(white, gameServer.uri().resolve("/games/MISSING"), "x".repeat(8193));
        assertEquals(413, directOversized.statusCode());
        assertEquals("Request too large", data(directOversized).get("message").getAsString());
    }

    @Test void concurrentJoinRequestsCannotClaimTheSameSeat() throws Exception {
        String whiteToken = token(white);
        var created = data(post(white, Map.of("token", whiteToken, "action", "create", "name", "Alice")));
        String code = created.getAsJsonObject("room").get("code").getAsString();
        HttpClient challenger = browser();
        var clients = List.of(black, challenger);
        var tokens = List.of(token(black), token(challenger));
        var pending = new ArrayList<CompletableFuture<HttpResponse<String>>>();
        for (int index = 0; index < clients.size(); index++) {
            var request = HttpRequest.newBuilder(gameServer.uri().resolve("/api/action"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(FormData.encode(
                            Map.of("action", "join", "token", tokens.get(index), "name", "Opponent", "code", code))))
                    .build();
            pending.add(clients.get(index).sendAsync(request, HttpResponse.BodyHandlers.ofString()));
        }
        var statuses = pending.stream().map(CompletableFuture::join).map(HttpResponse::statusCode).sorted().toList();

        assertEquals(List.of(200, 400), statuses);
        assertNotNull(rooms.execute(code).black());
    }

    @Test void bothPlayersCanReopenAndContinueAfterRestart(@TempDir Path directory) throws Exception {
        Path path = directory.resolve("rooms.sqlite");
        SqliteDatabase database = new SqliteDatabase(path);
        database.initialize();
        stop();
        start(new SqliteRoomRepository(database));
        String whiteToken = token(white);
        String blackToken = token(black);
        var createdResponse = createGame(white, whiteToken, "Alice");
        assertEquals(201, createdResponse.statusCode());
        var created = data(createdResponse);
        String code = created.getAsJsonObject("room").get("code").getAsString();
        assertEquals(200, post(black, Map.of("token", blackToken, "action", "join", "name", "Bob", "code", code)).statusCode());
        assertEquals(200, putGame(white, code, Map.of("token", whiteToken, "from", "e2", "to", "e4")).statusCode());
        var beforeRestart = data(move(black, blackToken, "e7", "e5"));
        stop();
        database = new SqliteDatabase(path);
        database.initialize();
        start(new SqliteRoomRepository(database));

        var savedGame = get(browser(), "/games/" + code);
        assertEquals(200, savedGame.statusCode());
        assertEquals(beforeRestart.get("room"), data(savedGame).get("room"));
        assertEquals(beforeRestart.get("pieces"), data(savedGame).get("pieces"));
        String newWhiteToken = token(white);
        String newBlackToken = token(black);
        assertNotEquals(whiteToken, newWhiteToken);
        assertNotEquals(blackToken, newBlackToken);
        HttpClient outsider = browser();
        assertEquals(400, post(outsider, Map.of("token", token(outsider), "action", "join", "name", "Alice", "code", code)).statusCode());
        var reopenedWhite = post(white, Map.of("token", newWhiteToken, "action", "join", "name", "Alice", "code", code));
        var reopenedBlack = post(black, Map.of("token", newBlackToken, "action", "join", "name", "Bob", "code", code));
        assertEquals(200, reopenedWhite.statusCode());
        assertEquals(200, reopenedBlack.statusCode());
        assertEquals(beforeRestart.get("room"), data(reopenedWhite).get("room"));
        assertEquals(beforeRestart.get("room"), data(reopenedBlack).get("room"));
        assertEquals(beforeRestart.get("pieces"), data(reopenedWhite).get("pieces"));
        assertEquals(200, move(white, newWhiteToken, "g1", "f3").statusCode());
        var continued = data(get(black, "/api/state")).getAsJsonObject("room");
        assertEquals(3, continued.getAsJsonArray("moves").size());
        assertEquals("Black", continued.get("turn").getAsString());
    }

    @Test void rejectsIgnoringCheckWithoutChangingTheGameAndAcceptsBlockingIt() throws Exception {
        String whiteToken = token(white);
        String blackToken = token(black);
        var created = data(post(white, Map.of("token", whiteToken, "action", "create", "name", "Alice")));
        String code = created.getAsJsonObject("room").get("code").getAsString();
        assertEquals(200, post(black, Map.of("token", blackToken, "action", "join", "name", "Bob", "code", code)).statusCode());
        assertEquals(200, move(white, whiteToken, "e2", "e4").statusCode());
        assertEquals(200, move(black, blackToken, "e7", "e5").statusCode());
        assertEquals(200, move(white, whiteToken, "f2", "f3").statusCode());
        var attack = move(black, blackToken, "d8", "h4");
        assertEquals(200, attack.statusCode());
        var attackedRoom = data(attack).getAsJsonObject("room");
        assertTrue(attackedRoom.get("inCheck").getAsBoolean());
        assertEquals("White", attackedRoom.get("turn").getAsString());
        assertEquals(attackedRoom, data(get(white, "/api/state")).getAsJsonObject("room"));
        Room inCheck = rooms.execute(code);

        var rejected = move(white, whiteToken, "a2", "a3");
        assertEquals(400, rejected.statusCode());
        assertEquals("Your king would be in check.", data(rejected).get("message").getAsString());
        assertSame(inCheck, rooms.execute(code));

        var blocked = move(white, whiteToken, "g2", "g3");
        assertEquals(200, blocked.statusCode());
        var updated = data(get(black, "/api/state"));
        assertEquals("Black", updated.getAsJsonObject("room").get("turn").getAsString());
        assertFalse(updated.getAsJsonObject("room").get("inCheck").getAsBoolean());
        assertEquals(5, updated.getAsJsonObject("room").getAsJsonArray("moves").size());
        assertEquals("WhitePawn", updated.getAsJsonObject("pieces").get("g3").getAsString());
    }

    @Test void reportsAnAbsentOpponentAndTheirReturnWithoutChangingTheGame() throws Exception {
        AtomicLong elapsed = new AtomicLong();
        InstantSource time = () -> Instant.ofEpochMilli(elapsed.get());
        stop();
        start(new InMemoryRoomRepository(), time.withZone(ZoneOffset.UTC));
        String whiteToken = token(white);
        String blackToken = token(black);
        var created = data(post(white, Map.of("token", whiteToken, "action", "create", "name", "Alice")));
        String code = created.getAsJsonObject("room").get("code").getAsString();
        var joined = post(black, Map.of("token", blackToken, "action", "join", "name", "Bob", "code", code));
        assertEquals(200, joined.statusCode());
        assertPresence(data(joined), true, true);
        var played = data(move(white, whiteToken, "e2", "e4"));

        elapsed.set(15_000);
        var inactive = data(get(white, "/api/state"));
        assertPresence(inactive, true, false);
        assertEquals(played.get("room"), inactive.get("room"));
        assertEquals(played.get("pieces"), inactive.get("pieces"));

        assertPresence(data(get(black, "/api/state")), true, true);
        assertPresence(data(get(white, "/api/state")), true, true);
        assertEquals(200, post(black, Map.of("token", blackToken, "action", "leave")).statusCode());
        assertPresence(data(get(white, "/api/state")), true, false);
        var returned = post(black, Map.of("token", blackToken, "action", "join", "name", "Bob", "code", code));
        assertEquals(200, returned.statusCode());
        assertPresence(data(returned), true, true);
        assertEquals(played.get("room"), data(returned).get("room"));
    }

    private void assertPresence(JsonObject snapshot, boolean whiteConnected, boolean blackConnected) {
        var presence = snapshot.getAsJsonObject("presence");
        assertEquals(whiteConnected, presence.get("whiteConnected").getAsBoolean());
        assertEquals(blackConnected, presence.get("blackConnected").getAsBoolean());
    }

    @Test void jsonSnapshotsPreserveUnicodeEscapingAndStructuredHistory() {
        User user = new User("a&b", "Zoë \"<&>\" +\n♟");
        Room waiting = Room.create("C&1", user);
        Room room = waiting.join(new User("b", "Renée"));
        room = room.move(user, new Move(Square.at("e2"), Square.at("e4")));
        RoomJsonCodec codec = new RoomJsonCodec();
        var snapshot = JsonParser.parseString(codec.snapshot(room, user, "token", "Ready", true, false)).getAsJsonObject();
        assertEquals(user.name(), snapshot.getAsJsonObject("viewer").get("name").getAsString());
        assertEquals("C&1", snapshot.getAsJsonObject("room").get("code").getAsString());
        assertEquals("e2", snapshot.getAsJsonObject("room").getAsJsonArray("moves").get(0).getAsJsonObject().get("from").getAsString());
        assertEquals("WhitePawn", snapshot.getAsJsonObject("pieces").get("e4").getAsString());
        assertEquals(0, waiting.game().moves().size());
        assertThrows(IllegalArgumentException.class, () -> FormData.decode("a=1&a=2"));
    }

    @Test void bothPlayersReceiveTheWinnerAfterCheckmate() throws Exception {
        String whiteToken = token(white);
        String blackToken = token(black);
        var created = data(post(white, Map.of("token", whiteToken, "action", "create", "name", "Alice")));
        String code = created.getAsJsonObject("room").get("code").getAsString();
        assertEquals(200, post(black, Map.of("token", blackToken, "action", "join", "name", "Bob", "code", code)).statusCode());
        assertEquals(200, move(white, whiteToken, "f2", "f3").statusCode());
        assertEquals(200, move(black, blackToken, "e7", "e5").statusCode());
        var beforeCheckmate = move(white, whiteToken, "g2", "g4");
        assertEquals(200, beforeCheckmate.statusCode());
        assertTrue(data(beforeCheckmate).getAsJsonObject("room").get("winner").isJsonNull());

        var checkmate = move(black, blackToken, "d8", "h4");
        assertEquals(200, checkmate.statusCode());
        var finalRoom = data(checkmate).getAsJsonObject("room");
        assertEquals("Black", finalRoom.get("winner").getAsString());
        assertFalse(finalRoom.get("stalemate").getAsBoolean());
        assertEquals(4, finalRoom.getAsJsonArray("moves").size());
        assertEquals(400, move(white, whiteToken, "a2", "a3").statusCode());
        assertEquals(finalRoom, data(get(white, "/api/state")).getAsJsonObject("room"));
    }

    @Test void bothPlayersReceiveStalemateWithoutAWinner() throws Exception {
        String whiteToken = token(white);
        String blackToken = token(black);
        var created = data(post(white, Map.of("token", whiteToken, "action", "create", "name", "Alice")));
        String code = created.getAsJsonObject("room").get("code").getAsString();
        assertEquals(200, post(black, Map.of("token", blackToken, "action", "join", "name", "Bob", "code", code)).statusCode());
        var moves = List.of("e2e3", "a7a5", "d1h5", "a8a6", "h5a5", "h7h5", "a5c7", "a6h6", "h2h4", "f7f6",
                "c7d7", "e8f7", "d7b7", "d8d3", "b7b8", "d3h7", "b8c8", "f7g6", "c8e6");
        for (int index = 0; index < moves.size(); index++) {
            String squares = moves.get(index);
            boolean whiteTurn = index % 2 == 0;
            var response = move(whiteTurn ? white : black, whiteTurn ? whiteToken : blackToken,
                    squares.substring(0, 2), squares.substring(2));
            assertEquals(200, response.statusCode(), squares);
            var room = data(response).getAsJsonObject("room");
            assertEquals(index == moves.size() - 1, room.get("stalemate").getAsBoolean(), squares);
            assertTrue(room.get("winner").isJsonNull(), squares);
        }

        var finalRoom = data(get(white, "/api/state")).getAsJsonObject("room");
        assertEquals(moves.size(), finalRoom.getAsJsonArray("moves").size());
        assertEquals(400, move(black, blackToken, "g6", "f5").statusCode());
        assertEquals(finalRoom, data(get(black, "/api/state")).getAsJsonObject("room"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void acceptsAPromotionChoiceAndReturnsItInBothPlayersHistory(boolean json) throws Exception {
        String whiteToken = token(white);
        String blackToken = token(black);
        var created = data(post(white, Map.of("token", whiteToken, "action", "create", "name", "Alice")));
        String code = created.getAsJsonObject("room").get("code").getAsString();
        assertEquals(200, post(black, Map.of("token", blackToken, "action", "join", "name", "Bob", "code", code)).statusCode());
        var moves = List.of("a2a4", "h7h5", "a4a5", "h5h4", "a5a6", "h4h3", "a6b7", "h3g2");
        for (int index = 0; index < moves.size(); index++) {
            String squares = moves.get(index);
            boolean whiteTurn = index % 2 == 0;
            assertEquals(200, move(whiteTurn ? white : black, whiteTurn ? whiteToken : blackToken,
                    squares.substring(0, 2), squares.substring(2)).statusCode(), squares);
        }
        Room beforePromotion = rooms.execute(code);
        var invalid = json
                ? putGame(white, code, Map.of("token", whiteToken, "from", "b7", "to", "a8", "promotion", "king"))
                : post(white, Map.of("token", whiteToken, "action", "move", "from", "b7", "to", "a8", "promotion", "king"));
        assertEquals(400, invalid.statusCode());
        assertSame(beforePromotion, rooms.execute(code));

        var response = json
                ? putGame(white, code, Map.of("token", whiteToken, "from", "b7", "to", "a8", "promotion", " Knight "))
                : post(white, Map.of("token", whiteToken, "action", "move", "from", "b7", "to", "a8", "promotion", " Knight "));
        assertEquals(200, response.statusCode());
        var promoted = data(response);
        var opponent = data(get(black, "/api/state"));
        assertEquals("WhiteKnight", promoted.getAsJsonObject("pieces").get("a8").getAsString());
        assertEquals(promoted.getAsJsonObject("pieces"), opponent.getAsJsonObject("pieces"));
        var history = promoted.getAsJsonObject("room").getAsJsonArray("moves");
        assertEquals("knight", history.get(history.size() - 1).getAsJsonObject().get("promotion").getAsString());
        assertEquals(history, opponent.getAsJsonObject("room").getAsJsonArray("moves"));
    }

    @Test void bothPlayersSeeAnEnPassantCaptureThroughAnOrdinaryMoveRequest() throws Exception {
        String whiteToken = token(white);
        String blackToken = token(black);
        var created = data(post(white, Map.of("token", whiteToken, "action", "create", "name", "Alice")));
        String code = created.getAsJsonObject("room").get("code").getAsString();
        assertEquals(200, post(black, Map.of("token", blackToken, "action", "join", "name", "Bob", "code", code)).statusCode());
        assertEquals(200, move(white, whiteToken, "e2", "e4").statusCode());
        assertEquals(200, move(black, blackToken, "a7", "a6").statusCode());
        assertEquals(200, move(white, whiteToken, "e4", "e5").statusCode());
        assertEquals(200, move(black, blackToken, "d7", "d5").statusCode());

        var response = move(white, whiteToken, "e5", "d6");
        assertEquals(200, response.statusCode());
        var captured = data(response);
        var board = captured.getAsJsonObject("pieces");
        assertEquals("WhitePawn", board.get("d6").getAsString());
        assertFalse(board.has("d5"));
        assertFalse(board.has("e5"));

        var opponent = data(get(black, "/api/state"));
        assertEquals(board, opponent.getAsJsonObject("pieces"));
        assertEquals(captured.getAsJsonObject("room"), opponent.getAsJsonObject("room"));
        assertEquals(5, opponent.getAsJsonObject("room").getAsJsonArray("moves").size());
        assertEquals("Black", opponent.getAsJsonObject("room").get("turn").getAsString());
    }

    @Test void castlingUpdatesBothPlayersAndPlayContinuesFromTheNewSquares() throws Exception {
        String whiteToken = token(white);
        String blackToken = token(black);
        var created = data(post(white, Map.of("token", whiteToken, "action", "create", "name", "Alice")));
        String code = created.getAsJsonObject("room").get("code").getAsString();
        assertEquals(200, post(black, Map.of("token", blackToken, "action", "join", "name", "Bob", "code", code)).statusCode());
        assertEquals(200, move(white, whiteToken, "e2", "e4").statusCode());
        assertEquals(200, move(black, blackToken, "e7", "e5").statusCode());
        assertEquals(200, move(white, whiteToken, "g1", "f3").statusCode());
        assertEquals(200, move(black, blackToken, "g8", "f6").statusCode());
        assertEquals(200, move(white, whiteToken, "f1", "e2").statusCode());
        assertEquals(200, move(black, blackToken, "f8", "e7").statusCode());

        var response = move(white, whiteToken, "e1", "g1");
        assertEquals(200, response.statusCode());
        var castled = data(response);
        var board = castled.getAsJsonObject("pieces");
        assertEquals("WhiteKing", board.get("g1").getAsString());
        assertEquals("WhiteRook", board.get("f1").getAsString());
        assertFalse(board.has("e1"));
        assertFalse(board.has("h1"));

        var opponent = data(get(black, "/api/state"));
        assertEquals(board, opponent.getAsJsonObject("pieces"));
        assertEquals(castled.getAsJsonObject("room"), opponent.getAsJsonObject("room"));
        assertEquals(7, opponent.getAsJsonObject("room").getAsJsonArray("moves").size());
        assertEquals("Black", opponent.getAsJsonObject("room").get("turn").getAsString());

        assertEquals(200, move(black, blackToken, "e8", "g8").statusCode());
        assertEquals(200, move(white, whiteToken, "g1", "h1").statusCode());
        assertEquals(200, move(black, blackToken, "d7", "d6").statusCode());
        var rookMoved = move(white, whiteToken, "f1", "e1");
        assertEquals(200, rookMoved.statusCode());
        var latest = data(rookMoved);
        assertEquals("WhiteKing", latest.getAsJsonObject("pieces").get("h1").getAsString());
        assertEquals("WhiteRook", latest.getAsJsonObject("pieces").get("e1").getAsString());
        assertEquals(11, latest.getAsJsonObject("room").getAsJsonArray("moves").size());
    }

    @Test void invalidActionsPreserveSessionAndRoom() throws Exception {
        String csrf = token(white);
        var created = data(post(white, Map.of("token", csrf, "action", "create", "name", "Alice")));
        String code = created.getAsJsonObject("room").get("code").getAsString();
        Room original = rooms.execute(code);
        assertEquals(400, post(white, Map.of("token", csrf, "action", "unknown")).statusCode());
        assertEquals(400, post(white, Map.of("token", csrf, "action", "join", "code", "MISSING", "name", "Renamed")).statusCode());
        assertEquals(400, post(white, Map.of("token", csrf, "action", "create", "name", "a".repeat(41))).statusCode());
        var current = data(get(white, "/api/state"));
        assertEquals("Alice", current.getAsJsonObject("viewer").get("name").getAsString());
        assertEquals(code, current.getAsJsonObject("room").get("code").getAsString());
        assertSame(original, rooms.execute(code));
    }

    @Test void usesCookieIdentityAndPreservesMovesAfterRejectedRequests() throws Exception {
        String whiteToken = token(white);
        String blackToken = token(black);
        var created = data(post(white, Map.of("token", whiteToken, "action", "create", "name", " Alice ")));
        String code = created.getAsJsonObject("room").get("code").getAsString();
        assertEquals("Alice", created.getAsJsonObject("room").getAsJsonObject("white").get("name").getAsString());
        assertEquals(200, post(black, Map.of("token", blackToken, "action", "join", "name", "Bob", "code", " " + code.toLowerCase() + " ")).statusCode());
        Room original = rooms.execute(code);
        assertEquals(400, post(black, Map.of("token", blackToken, "action", "move", "userId", created.getAsJsonObject("viewer").get("id").getAsString(), "from", "e2", "to", "e4")).statusCode());
        assertEquals(400, post(white, Map.of("token", whiteToken, "action", "move", "from", "a1", "to", "a2")).statusCode());
        assertEquals(400, post(white, Map.of("token", whiteToken, "action", "move", "from", "z9", "to", "a2")).statusCode());
        assertSame(original, rooms.execute(code));
        assertEquals(200, post(black, Map.of("token", blackToken, "action", "join", "name", "Bob", "code", code)).statusCode());
        assertSame(original, rooms.execute(code));
        HttpClient outsider = browser();
        String outsiderToken = token(outsider);
        assertEquals(400, post(outsider, Map.of("token", outsiderToken, "action", "join", "name", "Other", "code", code)).statusCode());
        assertTrue(data(get(outsider, "/api/state")).get("room").isJsonNull());
    }

    @Test void rejectsUnknownRoutesMethodsAndOversizedRequests() throws Exception {
        var missing = get(white, "/api/unknown");
        assertEquals(404, missing.statusCode());
        assertEquals("Not found", data(missing).get("message").getAsString());
        assertTrue(data(missing).get("token").isJsonNull());
        assertEquals(404, get(white, "/rooms").statusCode());
        assertEquals(404, get(white, "/assets/../pom.xml").statusCode());
        var wrongMethod = white.send(HttpRequest.newBuilder(server.uri().resolve("/api/state"))
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(405, wrongMethod.statusCode());
        assertEquals("Method not allowed", data(wrongMethod).get("message").getAsString());
        var oversized = post(white, Map.of("name", "a".repeat(8193)));
        assertEquals(413, oversized.statusCode());
        assertEquals("Request too large", data(oversized).get("message").getAsString());
        assertTrue(data(get(white, "/api/state")).get("room").isJsonNull());
    }
}
