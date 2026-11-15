package software.ulpgc.chess.backend;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.*;
import software.ulpgc.chess.application.domain.model.*;
import software.ulpgc.chess.backend.infrastructure.adapters.*;
import software.ulpgc.chess.backend.application.ports.RoomRepository;
import software.ulpgc.chess.backend.application.usecases.*;
import software.ulpgc.chess.backend.infrastructure.http.*;

import java.net.*;
import java.net.http.*;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RoomHttpIntegrationTest {
    private RoomHttpServer server;
    private GetRoomUseCase rooms;
    private final HttpClient white = browser();
    private final HttpClient black = browser();

    @BeforeEach void start() throws Exception {
        RoomRepository repository = new InMemoryRoomRepository();
        rooms = new GetRoomUseCase(repository);
        RoomApiHandler api = new RoomApiHandler(new CreateRoomUseCase(repository), rooms,
                new EnterRoomUseCase(rooms, new JoinRoomUseCase(repository)),
                new MoveUseCase(repository), new BrowserSessions(), new RoomJsonCodec());
        server = new RoomHttpServer(0, api, new StaticAssetHandler());
        server.start();
    }

    @AfterEach void stop() {
        if (server != null) server.close();
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
        assertEquals("application/json; charset=UTF-8", response.headers().firstValue("Content-Type").orElseThrow());
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    private String token(HttpClient client) throws Exception {
        return data(get(client, "/api/state")).get("token").getAsString();
    }

    private HttpResponse<String> move(HttpClient client, String token, String from, String to) throws Exception {
        return post(client, Map.of("token", token, "action", "move", "from", from, "to", to));
    }

    @Test void servesStaticMarkupJavascriptAndAllPieceAssets() throws Exception {
        var response = get(white, "/");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("A game, without haste."));
        assertTrue(response.body().contains("<script type=\"module\" src=\"/app.js\""));
        assertTrue(response.body().contains("id=\"square-template\""));
        assertFalse(response.body().contains("{{"));
        var preview = data(get(white, "/api/state"));
        assertTrue(preview.get("room").isJsonNull());
        assertEquals(32, preview.getAsJsonObject("pieces").size());
        assertEquals("Guest", preview.getAsJsonObject("viewer").get("name").getAsString());
        assertEquals(200, get(white, "/style.css").statusCode());
        assertEquals(200, get(white, "/app.js").statusCode());
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

    @Test void twoBrowserSessionsCreateJoinMoveAndRefreshOnOneServer() throws Exception {
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

    @Test void rejectsIgnoringCheckWithoutChangingTheGameAndAcceptsBlockingIt() throws Exception {
        String whiteToken = token(white);
        String blackToken = token(black);
        var created = data(post(white, Map.of("token", whiteToken, "action", "create", "name", "Alice")));
        String code = created.getAsJsonObject("room").get("code").getAsString();
        assertEquals(200, post(black, Map.of("token", blackToken, "action", "join", "name", "Bob", "code", code)).statusCode());
        assertEquals(200, move(white, whiteToken, "e2", "e4").statusCode());
        assertEquals(200, move(black, blackToken, "e7", "e5").statusCode());
        assertEquals(200, move(white, whiteToken, "f2", "f3").statusCode());
        assertEquals(200, move(black, blackToken, "d8", "h4").statusCode());
        Room inCheck = rooms.execute(code);

        var rejected = move(white, whiteToken, "a2", "a3");
        assertEquals(400, rejected.statusCode());
        assertEquals("Your king would be in check.", data(rejected).get("message").getAsString());
        assertSame(inCheck, rooms.execute(code));

        var blocked = move(white, whiteToken, "g2", "g3");
        assertEquals(200, blocked.statusCode());
        var updated = data(get(black, "/api/state"));
        assertEquals("Black", updated.getAsJsonObject("room").get("turn").getAsString());
        assertEquals(5, updated.getAsJsonObject("room").getAsJsonArray("moves").size());
        assertEquals("WhitePawn", updated.getAsJsonObject("pieces").get("g3").getAsString());
    }

    @Test void jsonSnapshotsPreserveUnicodeEscapingAndStructuredHistory() {
        User user = new User("a&b", "Zoë \"<&>\" +\n♟");
        Room waiting = Room.create("C&1", user);
        Room room = waiting.join(new User("b", "Renée"));
        room = room.move(user, new Move(Square.at("e2"), Square.at("e4")));
        RoomJsonCodec codec = new RoomJsonCodec();
        var snapshot = JsonParser.parseString(codec.snapshot(room, user, "token", "Ready")).getAsJsonObject();
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
