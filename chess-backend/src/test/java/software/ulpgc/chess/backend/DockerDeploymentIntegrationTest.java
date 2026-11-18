package software.ulpgc.chess.backend;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import software.ulpgc.chess.gameservice.infrastructure.http.FormData;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "regalia.docker.tests", matches = "true")
class DockerDeploymentIntegrationTest {
    private final String projectName = "regalia-test-" + UUID.randomUUID().toString().substring(0, 8);
    private Path repositoryRoot;
    private Path logDirectory;
    private URI origin;
    private int commandNumber;

    @BeforeAll void startIsolatedDeployment() throws Exception {
        repositoryRoot = findRepositoryRoot();
        logDirectory = Files.createTempDirectory(repositoryRoot.resolve("chess-backend/target"), "docker-check-");
        try (var socket = new ServerSocket(0)) {
            origin = URI.create("http://127.0.0.1:" + socket.getLocalPort());
        }
        runCompose("up", "-d", "--build");
        runCompose("exec", "-T", "nginx", "nginx", "-t");
        awaitGameService();
    }

    @AfterAll void stopIsolatedDeployment() throws Exception {
        if (origin == null) return;
        try {
            runCompose("logs", "--no-color");
        } finally {
            runCompose("down", "--volumes");
        }
    }

    @Test void servesTheFrontendAndPreservesCookiesHeadersAndRejectedRequests() throws Exception {
        try (var browser = newBrowser()) {
            var page = send(browser, "/", "GET", null);
            assertEquals(200, page.statusCode());
            assertTrue(page.body().contains("Saved profile name"));
            Path web = repositoryRoot.resolve("chess-frontend/src/main/resources/web");
            try (var paths = Files.walk(web)) {
                for (Path file : paths.filter(Files::isRegularFile).toList()) {
                    String path = "/" + web.relativize(file).toString().replace('\\', '/');
                    assertEquals(200, send(browser, path, "GET", null).statusCode(), path);
                }
            }
            var response = send(browser, "/api/state", "GET", null);
            assertTrue(response.headers().firstValue("Set-Cookie").orElseThrow().contains("HttpOnly"));
            var before = readJsonResponse(response, 200);
            String token = before.get("token").getAsString();
            assertEquals(before.get("viewer"), loadState(browser).get("viewer"));

            var rejected = readJsonResponse(sendJson(browser, "/api/profile", "PUT",
                    Map.of("token", "wrong", "name", "Rejected")), 403);
            assertEquals(token, rejected.get("token").getAsString());
            assertEquals(before, loadState(browser));
            readJsonResponse(send(browser, "/api/profile", "PUT", "{"), 400);
            var wrongMethod = send(browser, "/api/profile", "DELETE", null);
            readJsonResponse(wrongMethod, 405);
            assertEquals("GET, PUT", wrongMethod.headers().firstValue("Allow").orElseThrow());

            String update = encodeJson(Map.of("token", token, "name", "Boundary"));
            String boundary = update + " ".repeat(8192 - update.getBytes(StandardCharsets.UTF_8).length);
            readJsonResponse(send(browser, "/api/profile", "PUT", boundary), 200);
            readJsonResponse(send(browser, "/api/profile", "PUT", boundary + " "), 413);
            assertEquals("Boundary", loadState(browser).getAsJsonObject("viewer").get("name").getAsString());
            assertEquals(404, send(browser, "/players/resolve", "POST", "{}").statusCode());
            assertEquals(404, send(browser, "/README.md", "GET", null).statusCode());
        }
    }

    @Test void playsAndRecoversTheSameGameAndProfileAfterRecreatingTheJavaContainers() throws Exception {
        try (var white = newBrowser(); var black = newBrowser()) {
            var whiteState = loadState(white);
            var blackState = loadState(black);
            String whiteToken = whiteState.get("token").getAsString();
            String blackToken = blackState.get("token").getAsString();
            assertNotEquals(whiteState.get("viewer"), blackState.get("viewer"));
            var created = sendJson(white, "/games", "POST", Map.of("token", whiteToken, "name", "Historical Alice"));
            String code = readJsonResponse(created, 201).get("id").getAsString();
            assertEquals("/games/" + code, created.headers().firstValue("Location").orElseThrow());
            joinRoom(black, code, blackToken, "Bob");
            recordMove(white, code, whiteToken, "e2", "e4");
            recordMove(black, code, blackToken, "e7", "e5");
            var before = loadState(white);
            var updated = readJsonResponse(sendJson(white, "/api/profile", "PUT",
                    Map.of("token", whiteToken, "name", "Saved Alice")), 200);
            var after = loadState(white);
            assertEquals(before.get("room"), after.get("room"));
            assertEquals(before.get("pieces"), after.get("pieces"));
            assertEquals(updated.get("profile"), after.get("viewer"));

            runCompose("up", "-d", "--no-deps", "--force-recreate", "player-service", "game-service");
            awaitGameService();
            var restored = loadState(white);
            assertEquals(updated.get("profile"), restored.get("viewer"));
            assertNotEquals(whiteToken, restored.get("token").getAsString());
            String restoredToken = restored.get("token").getAsString();
            var reopened = joinRoom(white, code, restoredToken, "Returning Alice");
            assertEquals(before.get("room"), reopened.get("room"));
            assertEquals(before.get("pieces"), reopened.get("pieces"));
            var continued = recordMove(white, code, restoredToken, "g1", "f3");
            assertEquals(3, continued.getAsJsonObject("room").getAsJsonArray("moves").size());
            assertEquals("Historical Alice", continued.getAsJsonObject("room").getAsJsonObject("white").get("name").getAsString());
        }
    }

    @Test void returnsJsonWhenTheGameServiceIsStoppedOrDoesNotRespond() throws Exception {
        try (var browser = newBrowser()) {
            runCompose("stop", "game-service");
            try {
                var failure = readJsonResponse(send(browser, "/api/state", "GET", null), 503);
                assertTrue(failure.get("message").getAsString().contains("unavailable"));
                assertEquals(200, send(browser, "/", "GET", null).statusCode());
            } finally {
                runCompose("start", "game-service");
                awaitGameService();
            }
            runCompose("pause", "game-service");
            try {
                readJsonResponse(send(browser, "/api/state", "GET", null), 503);
            } finally {
                runCompose("unpause", "game-service");
                awaitGameService();
            }
        }
    }

    private Path findRepositoryRoot() {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null && !Files.isRegularFile(directory.resolve("compose.yaml"))) directory = directory.getParent();
        return java.util.Objects.requireNonNull(directory, "Run the Docker checks from the Maven project.");
    }

    private void runCompose(String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of("docker", "compose", "-p", projectName));
        command.addAll(List.of(arguments));
        Path output = logDirectory.resolve("command-" + ++commandNumber + ".log");
        var builder = new ProcessBuilder(command).directory(repositoryRoot.toFile())
                .redirectErrorStream(true).redirectOutput(output.toFile());
        builder.environment().put("REGALIA_PORT", Integer.toString(origin.getPort()));
        Process process = builder.start();
        try {
            assertTrue(process.waitFor(5, TimeUnit.MINUTES), "Docker command timed out. See " + output);
            assertEquals(0, process.exitValue(), "Docker command failed. See " + output);
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    private void awaitGameService() throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
        try (var browser = newBrowser()) {
            while (System.nanoTime() < deadline) {
                try {
                    if (send(browser, "/api/state", "GET", null).statusCode() == 200) return;
                } catch (java.io.IOException ignored) {}
                Thread.sleep(200);
            }
        }
        fail("The Docker game service did not become available. See " + logDirectory);
    }

    private HttpClient newBrowser() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
    }

    private HttpResponse<String> send(HttpClient browser, String path, String method, String body) throws Exception {
        var request = HttpRequest.newBuilder(origin.resolve(path)).timeout(Duration.ofSeconds(15));
        if (body == null) request.method(method, HttpRequest.BodyPublishers.noBody());
        else request.header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        return browser.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> sendJson(HttpClient browser, String path, String method, Map<String, String> fields) throws Exception {
        return send(browser, path, method, encodeJson(fields));
    }

    private String encodeJson(Map<String, String> fields) {
        JsonObject body = new JsonObject();
        fields.forEach(body::addProperty);
        return body.toString();
    }

    private JsonObject readJsonResponse(HttpResponse<String> response, int status) {
        assertEquals(status, response.statusCode(), response.body());
        assertTrue(response.headers().firstValue("Content-Type").orElseThrow()
                .toLowerCase(Locale.ROOT).startsWith("application/json"));
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    private JsonObject loadState(HttpClient browser) throws Exception {
        return readJsonResponse(send(browser, "/api/state", "GET", null), 200);
    }

    private JsonObject joinRoom(HttpClient browser, String code, String token, String name) throws Exception {
        var request = HttpRequest.newBuilder(origin.resolve("/api/action")).timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(FormData.encode(Map.of("action", "join", "code", code,
                        "token", token, "name", name)), StandardCharsets.UTF_8)).build();
        return readJsonResponse(browser.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)), 200);
    }

    private JsonObject recordMove(HttpClient browser, String code, String token, String from, String to) throws Exception {
        return readJsonResponse(sendJson(browser, "/games/" + code, "PUT",
                Map.of("token", token, "from", from, "to", to)), 200);
    }
}
