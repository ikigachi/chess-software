package software.ulpgc.chess.matchesservice;

import com.google.gson.JsonParser;
import io.javalin.Javalin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.ulpgc.chess.application.domain.model.*;
import software.ulpgc.chess.gameservice.application.ports.AccountAuthentication;
import software.ulpgc.chess.gameservice.application.ports.AccountRegistration;
import software.ulpgc.chess.gameservice.application.usecases.*;
import software.ulpgc.chess.gameservice.infrastructure.adapters.*;
import software.ulpgc.chess.gameservice.infrastructure.http.RoomJsonCodec;
import software.ulpgc.chess.matchesservice.application.usecases.GetMatchUseCase;
import software.ulpgc.chess.matchesservice.infrastructure.adapters.HttpGameReader;
import software.ulpgc.chess.matchesservice.infrastructure.adapters.MatchesHttpServer;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class MatchesHttpIntegrationTest {
    @TempDir Path directory;
    private final HttpClient client = HttpClient.newHttpClient();
    private SqliteDatabase database;
    private SqliteRoomRepository repository;
    private Room original;
    private Javalin source;
    private HttpGameReader games;
    private MatchesHttpServer matches;

    @BeforeEach void start() {
        database = new SqliteDatabase(directory.resolve("rooms.sqlite"));
        database.initialize();
        repository = new SqliteRoomRepository(database);
        var white = new User("alice", "Recorded Alice");
        var black = new User("bob", "Recorded Bob");
        original = new Room("ABC123", white, black, Game.initial("separate-game-id"))
                .move(white, new Move(Square.at("e2"), Square.at("e4")))
                .move(black, new Move(Square.at("e7"), Square.at("e5")));
        repository.save(original);
        startServices();
    }

    private void startServices() {
        var unusedAccounts = new UnusedAccountServices();
        var sessions = new BrowserSessions(credential -> { throw new AssertionError("A query resolved a guest."); },
                new AccountAccessUseCases(unusedAccounts, unusedAccounts));
        var handler = new GameApiHandler(new CreateRoomUseCase(repository), new CreateRoomOnceUseCase(repository), new GetRoomUseCase(repository),
                new MoveUseCase(repository), sessions, new RoomJsonCodec());
        source = Javalin.create(config -> config.routes.get("/games/{id}", handler::retrieve)).start("127.0.0.1", 0);
        games = new HttpGameReader(URI.create("http://127.0.0.1:" + source.port()));
        matches = new MatchesHttpServer(0, new GetMatchUseCase(games));
        matches.start();
    }

    @AfterEach void stop() {
        if (matches != null) matches.close();
        if (games != null) games.close();
        if (source != null) source.stop();
        client.close();
    }

    private HttpResponse<String> query(String code) throws Exception {
        return client.send(HttpRequest.newBuilder(matches.uri().resolve("/matches/" + code))
                .header("Cookie", "regalia=untrusted-cookie").GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test void queriesRecordedParticipantsAndCurrentGameWithoutCreatingSessionsOrWritingStorage() throws Exception {
        try (var connection = database.openConnection(); var statement = connection.createStatement()) {
            for (String table : new String[] {"rooms", "moves"})
                for (String operation : new String[] {"INSERT", "UPDATE", "DELETE"})
                    statement.executeUpdate("CREATE TRIGGER reject_" + table + "_" + operation + " BEFORE " + operation
                            + " ON " + table + " BEGIN SELECT RAISE(ABORT, 'Queries must not write'); END");
        }

        var response = query("abc123");

        assertEquals(200, response.statusCode());
        var match = JsonParser.parseString(response.body()).getAsJsonObject();
        assertEquals("ABC123", match.get("code").getAsString());
        assertEquals("separate-game-id", match.get("gameId").getAsString());
        assertEquals("Recorded Alice", match.getAsJsonObject("white").get("name").getAsString());
        assertEquals("Recorded Bob", match.getAsJsonObject("black").get("name").getAsString());
        assertEquals("White", match.get("turn").getAsString());
        assertEquals(2, match.get("moveCount").getAsInt());
        assertFalse(match.has("moves"));
        assertFalse(match.has("pieces"));
        assertFalse(match.has("token"));
        assertTrue(response.headers().firstValue("Set-Cookie").isEmpty());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        assertEquals(original, repository.findByCode("ABC123").orElseThrow());
    }

    @Test void aWaitingMatchIsReturnedWithoutInventingAnOpponentOrMoves() throws Exception {
        repository.save(Room.create("WAIT01", new User("creator", "Original creator")));

        var response = query("WAIT01");

        assertEquals(200, response.statusCode());
        var match = JsonParser.parseString(response.body()).getAsJsonObject();
        assertTrue(match.get("black").isJsonNull());
        assertEquals(0, match.get("moveCount").getAsInt());
        assertTrue(repository.findByCode("WAIT01").orElseThrow().game().moves().isEmpty());
    }

    @Test void missingMatchesInvalidInputAndUnsupportedWritesNeverChangeExistingGames() throws Exception {
        assertEquals(404, query("MISSING").statusCode());
        assertEquals(400, query("A".repeat(31)).statusCode());
        for (String method : new String[] {"POST", "PUT", "DELETE"}) {
            var rejected = client.send(HttpRequest.newBuilder(matches.uri().resolve("/matches/ABC123"))
                    .method(method, HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(405, rejected.statusCode());
            assertEquals("GET", rejected.headers().firstValue("Allow").orElseThrow());
            assertTrue(JsonParser.parseString(rejected.body()).getAsJsonObject().has("message"));
        }
        assertTrue(repository.findByCode("MISSING").isEmpty());
        assertEquals(original, repository.findByCode("ABC123").orElseThrow());
    }

    @Test void anUnavailableGameSourceReturns503AndCanRecoverWithoutAStoredMatchCopy() throws Exception {
        source.stop();
        assertEquals(503, query("ABC123").statusCode());
        matches.close();
        games.close();
        repository = new SqliteRoomRepository(new SqliteDatabase(directory.resolve("rooms.sqlite")));
        startServices();

        assertEquals(200, query("ABC123").statusCode());
        assertEquals(original, repository.findByCode("ABC123").orElseThrow());
        try (var files = java.nio.file.Files.list(directory)) {
            assertEquals(1, files.filter(path -> path.getFileName().toString().endsWith(".sqlite")).count());
        }
    }

    @Test void mainRunsTheReadQueryInAnIndependentJavaProcess() throws Exception {
        Path output = directory.resolve("matches-main.log");
        String executable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var process = new ProcessBuilder(executable, "-cp", System.getProperty("java.class.path"),
                Main.class.getName(), "0", "http://127.0.0.1:" + source.port())
                .directory(directory.toFile()).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            var address = Pattern.compile("Matches service: (http://127\\.0\\.0\\.1:[0-9]+)");
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            URI origin = null;
            while (System.nanoTime() < deadline && process.isAlive()) {
                var match = address.matcher(Files.readString(output, StandardCharsets.UTF_8));
                if (match.find()) {
                    origin = URI.create(match.group(1));
                    break;
                }
                Thread.sleep(50);
            }
            assertNotNull(origin, () -> "Matches Main did not start: " + readProcessOutput(output));
            var response = client.send(HttpRequest.newBuilder(origin.resolve("/matches/ABC123")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertEquals(2, JsonParser.parseString(response.body()).getAsJsonObject().get("moveCount").getAsInt());
            assertTrue(response.headers().firstValue("Set-Cookie").isEmpty());
            assertEquals(original, repository.findByCode("ABC123").orElseThrow());
        } finally {
            process.destroy();
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                assertTrue(process.waitFor(10, TimeUnit.SECONDS));
            }
        }
    }

    private String readProcessOutput(Path output) {
        try { return Files.readString(output, StandardCharsets.UTF_8); }
        catch (java.io.IOException exception) { return exception.getMessage(); }
    }

    private static final class UnusedAccountServices implements AccountAuthentication, AccountRegistration {
        @Override public Grant login(String username, String password) { throw new AssertionError(); }
        @Override public Identity resume(String token) { throw new AssertionError(); }
        @Override public void logout(String token) { throw new AssertionError(); }
        @Override public boolean hasAccount(String playerId) { throw new AssertionError(); }
        @Override public String register(String credential, String username, String password) { throw new AssertionError(); }
        @Override public String deactivate(String token, String password) { throw new AssertionError(); }
    }
}
