package software.ulpgc.chess.matchesservice;

import com.google.gson.JsonParser;
import io.javalin.Javalin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.ulpgc.chess.application.domain.model.*;
import software.ulpgc.chess.gameservice.infrastructure.http.RoomJsonCodec;
import software.ulpgc.chess.matchesservice.infrastructure.adapters.HttpGameReader;

import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class HttpGameReaderTest {
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicReference<String> body = new AtomicReference<>("{}");
    private final AtomicReference<String> requestedCode = new AtomicReference<>();
    private Javalin source;
    private HttpGameReader games;

    @BeforeEach void start() {
        source = Javalin.create(config -> config.routes.get("/games/{id}", context -> {
            requestedCode.set(context.pathParam("id"));
            assertNull(context.header("Cookie"));
            assertNull(context.header("Authorization"));
            context.status(status.get()).contentType("application/json").result(body.get());
        })).start("127.0.0.1", 0);
        games = new HttpGameReader(URI.create("http://127.0.0.1:" + source.port()));
    }

    @AfterEach void stop() {
        if (games != null) games.close();
        if (source != null) source.stop();
    }

    @Test void reconstructsTheSharedDomainAndPreservesDistinctGameIdentifiersAndPromotions() {
        var white = new User("alice", "Recorded Alice");
        var black = new User("bob", "Recorded Bob");
        var moves = List.of(new Move(Square.at("a2"), Square.at("a4")), new Move(Square.at("h7"), Square.at("h5")),
                new Move(Square.at("a4"), Square.at("a5")), new Move(Square.at("h5"), Square.at("h4")),
                new Move(Square.at("a5"), Square.at("a6")), new Move(Square.at("h4"), Square.at("h3")),
                new Move(Square.at("a6"), Square.at("b7")), new Move(Square.at("h3"), Square.at("g2")),
                new Move(Square.at("b7"), Square.at("a8"), Promotion.Queen));
        var room = new Room("ABC123", white, black, Game.replay("separate-game-id", moves));
        body.set(new RoomJsonCodec().game(room));

        var restored = games.findByRoomCode("ABC123").orElseThrow();

        assertEquals(room, restored);
        assertEquals("ABC123", requestedCode.get());
        assertEquals(Piece.WhiteQueen, restored.game().pieceAt(Square.at("a8")));
    }

    @Test void only404MeansTheGameIsAbsentAndUnavailableServicesRemainFailures() {
        status.set(404);
        assertTrue(games.findByRoomCode("ABC123").isEmpty());
        for (int rejected : new int[] {301, 400, 401, 403, 500, 503}) {
            status.set(rejected);
            var failure = assertThrows(IllegalStateException.class, () -> games.findByRoomCode("ABC123"));
            assertNotNull(failure.getCause());
        }
        source.stop();
        assertThrows(IllegalStateException.class, () -> games.findByRoomCode("ABC123"));
    }

    @Test void malformedIncompleteAndInconsistentGameResponsesNeverBecomeValidMatches() {
        var room = Room.create("ABC123", new User("alice", "Recorded Alice"));
        String valid = new RoomJsonCodec().game(room);
        for (String invalid : List.of("null", "[]", "{}", "{", "x".repeat(1024 * 1024 + 1),
                valid.replace("ABC123", "OTHER"), valid.replace("\"black\":null,", ""),
                valid.replace("\"moves\":[]", "\"moves\":null"),
                valid.replace("\"moves\":[]", "\"moves\":[{\"from\":\"e2\",\"to\":\"e4\"}]"))) {
            body.set(invalid);
            assertThrows(IllegalStateException.class, () -> games.findByRoomCode("ABC123"));
        }
        var invalidMove = JsonParser.parseString(valid).getAsJsonObject();
        invalidMove.getAsJsonObject("room").add("black", JsonParser.parseString("{\"id\":\"bob\",\"name\":\"Bob\"}"));
        invalidMove.getAsJsonObject("room").add("moves", JsonParser.parseString("[{\"from\":\"e2\",\"to\":\"e5\"}]"));
        body.set(invalidMove.toString());
        assertThrows(IllegalStateException.class, () -> games.findByRoomCode("ABC123"));
        invalidMove.getAsJsonObject("room").add("moves", JsonParser.parseString("[]"));
        invalidMove.getAsJsonObject("room").getAsJsonObject("black").addProperty("id", "alice");
        body.set(invalidMove.toString());
        assertThrows(IllegalStateException.class, () -> games.findByRoomCode("ABC123"));
    }

    @Test void rejectsOriginsThatCouldChangeTheServiceBoundary() {
        for (String origin : List.of("file:///tmp/games", "http://user@localhost", "http://localhost/games",
                "http://localhost?target=other", "http://localhost#fragment"))
            assertThrows(IllegalArgumentException.class, () -> new HttpGameReader(URI.create(origin)));
    }

    @Test void aResponseThatStallsAfterItsHeadersDoesNotBlockTheQueryIndefinitely() {
        games.close();
        source.stop();
        var releaseBody = new CountDownLatch(1);
        byte[] response = new RoomJsonCodec().game(Room.create("ABC123", new User("alice", "Alice")))
                .getBytes(StandardCharsets.UTF_8);
        source = Javalin.create(config -> config.routes.get("/games/{id}", context -> {
            context.contentType("application/json");
            var output = context.res().getOutputStream();
            output.write(response, 0, 1);
            output.flush();
            releaseBody.await(7, TimeUnit.SECONDS);
            output.write(response, 1, response.length - 1);
        })).start("127.0.0.1", 0);
        games = new HttpGameReader(URI.create("http://127.0.0.1:" + source.port()));
        try {
            var failure = assertThrows(IllegalStateException.class, () -> games.findByRoomCode("ABC123"));
            assertInstanceOf(HttpTimeoutException.class, failure.getCause());
        } finally { releaseBody.countDown(); }
    }
}
